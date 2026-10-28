package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.exception.LoginRejectedException;
import com.example.farm.common.api.ResultCode;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.common.utils.PasswordMigrationUtil;
import com.example.farm.entity.User;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.ChangePasswordDTO;
import com.example.farm.entity.dto.BootstrapTokenDTO;
import com.example.farm.entity.dto.LoginResultDTO;
import com.example.farm.entity.dto.PasswordMigrateResultDTO;
import com.example.farm.entity.dto.PasswordStatusResultDTO;
import com.example.farm.entity.dto.UserLoginDTO;
import com.example.farm.entity.dto.UserQueryDTO;
import com.example.farm.entity.dto.UserRegisterDTO;
import com.example.farm.entity.dto.UserUpdateDTO;
import com.example.farm.entity.dto.FirstAdminSetupStatusDTO;
import com.example.farm.entity.vo.UserVO;
import com.example.farm.mapper.UserMapper;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.BootstrapTokenService;
import com.example.farm.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 用户服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_OPERATOR = "OPERATOR";

    private final PasswordEncoder passwordEncoder;
    private final LoginProtectUtil loginProtectUtil;

    @Autowired(required = false)
    private BootstrapTokenService bootstrapTokenService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    /**
     * 正式 Spring 运行时由 MyBatis 注入；旧的用户认证单元测试未提供该替身时，
     * 仍允许只验证用户认证流程。真实 Mapper 一旦注入，目录写入失败会继续抛出。
     */
    @Autowired(required = false)
    private PrintFileMapper printFileMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private com.example.farm.service.LoginSessionService loginSessionService;

    private String issueToken(User user) {
        String sessionId = loginSessionService == null ? null : loginSessionService.create(user);
        return JwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole(), currentTokenVersion(user), sessionId);
    }

    private void invalidateSessions(User user) {
        if (loginSessionService != null) loginSessionService.revokeAll(user.getId(), null);
    }

    @Override
    @Transactional
    public void resetPassword(Long userId, com.example.farm.entity.dto.ResetPasswordDTO request) {
        assertAdmin(com.example.farm.common.utils.SecurityContextUtil.getCurrentUserId());
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException(400, "两次输入的密码不一致");
        }
        if (userId.equals(com.example.farm.common.utils.SecurityContextUtil.getCurrentUserId())) {
            throw new BusinessException(409, "请在个人中心修改自己的密码");
        }
        User user = getUserForUpdate(userId);
        if (user == null) throw new BusinessException(404, "用户不存在");
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setMustChangePassword(true);
        incrementTokenVersion(user);
        updateUserOrThrow(user, "重置密码失败");
        invalidateSessions(user);
        loginProtectUtil.clearLoginFail(user.getUsername());
        recordSuccess("USER_PASSWORD_RESET", "USER", userId, user.getUsername());
    }

    /**
     * Local Edition 首次安装允许初始化管理员；Server Edition 使用初始化 SQL 中的管理员账号。
     */
    @Value("${farm.security.first-admin-setup-enabled:false}")
    private boolean firstAdminSetupEnabled;

    /**
     * Local Edition 是单后端进程，多客户端请求仍可能同时到达；锁住首次初始化窗口，避免并发创建多个管理员。
     */
    private final Object firstAdminSetupLock = new Object();

    @Value("${jwt.expire-time:604800000}")
    private Long jwtExpireTime;

    @Override
    @Transactional(noRollbackFor = LoginRejectedException.class)
    public LoginResultDTO login(UserLoginDTO loginDTO) {
        String username = loginDTO.getUsername();

        if (loginProtectUtil.isLocked(username)) {
            long remainingMinutes = loginProtectUtil.getRemainingLockTime(username);
            recordLoginFailure(username, null, "ACCOUNT_LOCKED");
            throw authenticationFailure("账号已锁定，请 " + remainingMinutes + " 分钟后重试");
        }

        User user = findByUsername(username);
        if (user == null) {
            loginProtectUtil.recordLoginFail(username);
            recordLoginFailure(username, null, "INVALID_CREDENTIALS");
            throw authenticationFailure("账号或密码错误");
        }

        User lockedUser = getBaseMapper().selectByIdForUpdate(user.getId());
        if (lockedUser != null) {
            user = lockedUser;
        }

        if (Boolean.FALSE.equals(user.getEnabled())
                || (user.getEnabled() == null && loginProtectUtil.isUserDisabled(user.getId()))) {
            recordLoginFailure(user.getUsername(), user.getId(), "ACCOUNT_DISABLED");
            throw new LoginRejectedException(ResultCode.FORBIDDEN.getCode(), "用户已被禁用，请联系管理员");
        }

        PasswordMigrationUtil.MigrateResult verifyResult = PasswordMigrationUtil.matchesAndMigrate(
                loginDTO.getPassword(), user.getPasswordHash());

        if (!verifyResult.isMatches()) {
            int failCount = loginProtectUtil.recordLoginFail(username);
            int remainingAttempts = 5 - failCount;
            if (remainingAttempts > 0) {
                recordLoginFailure(user.getUsername(), user.getId(), "INVALID_CREDENTIALS");
                throw authenticationFailure("账号或密码错误，还剩 " + remainingAttempts + " 次机会");
            }
            recordLoginFailure(user.getUsername(), user.getId(), "ACCOUNT_LOCKED");
            throw authenticationFailure("账号已锁定，请 15 分钟后重试");
        }

        loginProtectUtil.clearLoginFail(username);

        if (verifyResult.needMigration()) {
            user.setPasswordHash(verifyResult.getNewHash());
            updateUserOrThrow(user, "用户密码自动迁移失败");
            log.info("用户密码已自动迁移为加密存储: username={}", user.getUsername());
        }

        String token = issueToken(user);
        LoginResultDTO result = new LoginResultDTO(
                token,
                jwtExpireTime / 1000,
                user.getId(),
                user.getUsername(),
                user.getRole()
        );
        result.setEmail(user.getEmail());
        result.setPhone(user.getPhone());
        result.setMustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()));
        result.setPermissions(com.example.farm.common.utils.RolePermissions.forRole(user.getRole()));

        recordLoginSuccess(user.getId(), user.getUsername(), user.getRole());
        log.info("用户登录成功: username={}", user.getUsername());
        return result;
    }

    @Override
    public FirstAdminSetupStatusDTO getFirstAdminSetupStatus() {
        if (!firstAdminSetupEnabled) {
            return new FirstAdminSetupStatusDTO(count() > 0, false);
        }

        boolean initialized = isSetupStateInitialized() || count() > 0;
        if (initialized && bootstrapTokenService != null) {
            bootstrapTokenService.invalidate();
        }
        return new FirstAdminSetupStatusDTO(initialized, firstAdminSetupEnabled && !initialized);
    }

    @Override
    public BootstrapTokenDTO getBootstrapToken() {
        synchronized (firstAdminSetupLock) {
            if (!firstAdminSetupEnabled || isSetupStateInitialized() || count() > 0) {
                return null;
            }
            return bootstrapTokenService == null ? null : bootstrapTokenService.getOrRotateCredential();
        }
    }

    @Override
    @Transactional
    public LoginResultDTO setupFirstAdmin(UserRegisterDTO setupDTO) {
        if (!firstAdminSetupEnabled) {
            throw new BusinessException(404, "首次管理员初始化未开启");
        }

        synchronized (firstAdminSetupLock) {
            if (count() > 0) {
                throw new BusinessException(409, "系统已完成初始化，请登录后由管理员创建账号");
            }
            if (isSetupStateInitialized()) {
                throw new BusinessException(409, "系统已完成初始化，请登录后由管理员创建账号");
            }
            if (!setupDTO.isPasswordMatch()) {
                throw new BusinessException("两次输入的密码不一致");
            }
            if (bootstrapTokenService == null
                    || !bootstrapTokenService.matches(setupDTO.getBootstrapToken())) {
                throw new BusinessException(403, "只能使用服务器本机提供的有效初始化凭据");
            }
            if (isUsernameExists(setupDTO.getUsername())) {
                throw new BusinessException("用户名已被使用");
            }

            User admin = new User();
            admin.setUsername(setupDTO.getUsername());
            admin.setPasswordHash(passwordEncoder.encode(setupDTO.getPassword()));
            admin.setEmail(setupDTO.getEmail());
            admin.setPhone(setupDTO.getPhone());
            admin.setRole(ROLE_ADMIN);
            admin.setEnabled(true);

            if (!save(admin)) {
                throw new BusinessException("管理员创建失败");
            }
            createDefaultFolder(admin.getId(), admin.getUsername());
            markSetupInitialized(admin.getId());
            invalidateBootstrapTokenAfterCommit();

            LoginResultDTO result = new LoginResultDTO(
                    issueToken(admin),
                    jwtExpireTime / 1000,
                    admin.getId(),
                    admin.getUsername(),
                    admin.getRole()
            );
            result.setEmail(admin.getEmail());
            result.setPhone(admin.getPhone());
            result.setPermissions(com.example.farm.common.utils.RolePermissions.forRole(admin.getRole()));
            recordLoginSuccess(admin.getId(), admin.getUsername(), admin.getRole());
            log.info("首次管理员初始化成功: userId={}, username={}", admin.getId(), admin.getUsername());
            return result;
        }
    }

    @Override
    @Transactional
    public Long register(UserRegisterDTO registerDTO) {
        if (!registerDTO.isPasswordMatch()) {
            throw new BusinessException("两次输入的密码不一致");
        }
        if (isUsernameExists(registerDTO.getUsername())) {
            throw new BusinessException("用户名已被注册");
        }
        if (StringUtils.isNotBlank(registerDTO.getEmail()) && isEmailExists(registerDTO.getEmail())) {
            throw new BusinessException("邮箱已被注册");
        }

        User newUser = new User();
        newUser.setUsername(registerDTO.getUsername());
        newUser.setPasswordHash(passwordEncoder.encode(registerDTO.getPassword()));
        newUser.setEmail(registerDTO.getEmail());
        newUser.setPhone(registerDTO.getPhone());
        String role = registerDTO.getRole() == null ? ROLE_OPERATOR : normalizeRole(registerDTO.getRole());
        if (ROLE_ADMIN.equals(role)) throw new BusinessException(400, "新建员工角色只能为操作员或查看员");
        newUser.setRole(role);
        newUser.setMustChangePassword(true);
        newUser.setEnabled(true);

        if (!save(newUser)) {
            throw new BusinessException("用户创建失败");
        }
        createDefaultFolder(newUser.getId(), newUser.getUsername());
        recordSuccess("USER_CREATE", "USER", newUser.getId(), newUser.getUsername());
        log.info("用户注册成功: username={}, id={}", newUser.getUsername(), newUser.getId());
        return newUser.getId();
    }

    private void createDefaultFolder(Long userId, String username) {
        if (printFileMapper == null) {
            return;
        }
        PrintFile defaultFolder = new PrintFile();
        defaultFolder.setParentId(null);
        defaultFolder.setIsFolder(true);
        defaultFolder.setIsDefaultFolder(true);
        defaultFolder.setOriginalName(username + "的文件");
        defaultFolder.setSafeName("folder_default_" + userId);
        defaultFolder.setUserId(userId);
        if (printFileMapper.insert(defaultFolder) <= 0) {
            throw new BusinessException("默认文件夹创建失败");
        }
    }

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordDTO changePasswordDTO) {
        if (!changePasswordDTO.isNewPasswordMatch()) {
            throw new BusinessException("两次输入的新密码不一致");
        }

        User user = getUserForUpdate(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        boolean matches;
        if (PasswordMigrationUtil.isEncrypted(user.getPasswordHash())) {
            matches = passwordEncoder.matches(changePasswordDTO.getOldPassword(), user.getPasswordHash());
        } else {
            matches = changePasswordDTO.getOldPassword().equals(user.getPasswordHash());
        }

        if (!matches) {
            throw new BusinessException("原密码错误");
        }
        if (passwordEncoder.matches(changePasswordDTO.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(changePasswordDTO.getNewPassword()));
        boolean forced = Boolean.TRUE.equals(user.getMustChangePassword());
        user.setMustChangePassword(false);
        incrementTokenVersion(user);
        updateUserOrThrow(user, "用户修改密码失败");
        invalidateSessions(user);
        recordSuccess(forced ? "USER_FIRST_PASSWORD_CHANGE" : "USER_PASSWORD_CHANGE", "USER", userId, user.getUsername());
        log.info("用户修改密码成功: username={}", user.getUsername());
    }

    @Override
    @Transactional
    public void updateUserInfo(UserUpdateDTO updateDTO) {
        if (StringUtils.isNotBlank(updateDTO.getRole())) {
            lockEnabledAdminIds();
        }
        User user = getUserForUpdate(updateDTO.getId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        if (StringUtils.isNotBlank(updateDTO.getEmail()) && !updateDTO.getEmail().equals(user.getEmail())) {
            if (isEmailExists(updateDTO.getEmail())) {
                throw new BusinessException("邮箱已被其他用户使用");
            }
            user.setEmail(updateDTO.getEmail());
        }

        if (StringUtils.isNotBlank(updateDTO.getPhone())) {
            user.setPhone(updateDTO.getPhone());
        }

        String previousRole = user.getRole();
        boolean roleChanged = false;
        if (StringUtils.isNotBlank(updateDTO.getRole())) {
            String nextRole = normalizeRole(updateDTO.getRole());
            roleChanged = !nextRole.equalsIgnoreCase(user.getRole());
            if (roleChanged && ROLE_ADMIN.equalsIgnoreCase(user.getRole())
                    && Boolean.TRUE.equals(user.getEnabled())
                    && !ROLE_ADMIN.equals(nextRole)) {
                assertNotLastActiveAdmin(user.getId());
            }
            user.setRole(nextRole);
        }

        if (roleChanged) incrementTokenVersion(user);

        updateUserOrThrow(user, "用户信息更新失败");
        if (roleChanged) {
            invalidateSessions(user);
            recordSuccess("USER_ROLE_CHANGE", "USER", user.getId(), previousRole + " -> " + user.getRole());
        }
        recordSuccess("USER_UPDATE", "USER", user.getId(), user.getUsername());
        log.info("用户信息更新成功: username={}", user.getUsername());
    }

    @Override
    public UserVO getCurrentUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return UserVO.from(user);
    }

    @Override
    public IPage<UserVO> pageUsers(UserQueryDTO queryDTO) {
        if (queryDTO == null) {
            throw new BusinessException(400, "用户查询参数不能为空");
        }
        Page<User> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.isNotBlank(queryDTO.getUsername())) {
            wrapper.like(User::getUsername, queryDTO.getUsername());
        }
        if (StringUtils.isNotBlank(queryDTO.getRole())) {
            wrapper.eq(User::getRole, queryDTO.getRole());
        }
        if (StringUtils.isNotBlank(queryDTO.getEmail())) {
            wrapper.like(User::getEmail, queryDTO.getEmail());
        }
        if (queryDTO.getEnabled() != null) {
            wrapper.eq(User::getEnabled, queryDTO.getEnabled());
        }

        wrapper.orderByDesc(User::getCreatedAt);
        IPage<User> result = page(page, wrapper);
        Page<UserVO> safePage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        safePage.setRecords(result.getRecords().stream().map(UserVO::from).toList());
        return safePage;
    }

    @Override
    @Transactional
    public UserVO disableUser(Long userId, Long adminId) {
        lockEnabledAdminIds();
        User user = getUserForUpdate(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (userId.equals(adminId)) {
            throw new BusinessException(409, "不能禁用当前登录账号");
        }

        assertAdmin(adminId);
        if (!Boolean.FALSE.equals(user.getEnabled())) {
            if (ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
                assertNotLastActiveAdmin(user.getId());
            }
            user.setEnabled(false);
            incrementTokenVersion(user);
            updateUserOrThrow(user, "用户禁用失败");
            invalidateSessions(user);
        }
        loginProtectUtil.disableUser(userId);
        loginProtectUtil.clearLoginFail(user.getUsername());
        recordSuccess("USER_DISABLE", "USER", user.getId(), user.getUsername());
        log.info("用户已被禁用: targetUserId={}, adminId={}", userId, adminId);
        return UserVO.from(user);
    }

    @Override
    @Transactional
    public UserVO enableUser(Long userId, Long adminId) {
        lockEnabledAdminIds();
        User user = getUserForUpdate(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        assertAdmin(adminId);
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            user.setEnabled(true);
            incrementTokenVersion(user);
            updateUserOrThrow(user, "用户启用失败");
        }
        loginProtectUtil.enableUser(userId);
        recordSuccess("USER_ENABLE", "USER", user.getId(), user.getUsername());
        log.info("用户已被启用: targetUserId={}, adminId={}", userId, adminId);
        return UserVO.from(user);
    }

    @Override
    public boolean isUsernameExists(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return count(wrapper) > 0;
    }

    @Override
    public boolean isEmailExists(String email) {
        if (StringUtils.isBlank(email)) {
            return false;
        }
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, email);
        return count(wrapper) > 0;
    }

    @Override
    @Transactional
    public PasswordMigrateResultDTO migrateAllPasswords() {
        int migratedCount = 0;
        int skippedCount = 0;

        for (User user : list()) {
            String currentHash = user.getPasswordHash();
            if (PasswordMigrationUtil.isEncrypted(currentHash)) {
                skippedCount++;
                continue;
            }

            if (currentHash != null && !currentHash.isEmpty()) {
                user.setPasswordHash(passwordEncoder.encode(currentHash));
                incrementTokenVersion(user);
                updateUserOrThrow(user, "用户密码迁移失败");
                migratedCount++;
                log.info("用户密码已迁移: username={}", user.getUsername());
            }
        }

        return new PasswordMigrateResultDTO(migratedCount, skippedCount, migratedCount + skippedCount);
    }

    @Override
    public PasswordStatusResultDTO checkPasswordStatus() {
        int encryptedCount = 0;
        int plainCount = 0;

        for (User user : list()) {
            if (PasswordMigrationUtil.isEncrypted(user.getPasswordHash())) {
                encryptedCount++;
            } else {
                plainCount++;
            }
        }

        return new PasswordStatusResultDTO(encryptedCount, plainCount, encryptedCount + plainCount);
    }

    private User findByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return getOne(wrapper);
    }

    private BusinessException authenticationFailure(String message) {
        return new LoginRejectedException(ResultCode.UNAUTHORIZED.getCode(), message);
    }

    private void recordSuccess(String action, String targetType, Object targetId, String targetLabel) {
        if (auditLogService != null) {
            auditLogService.recordSuccess(action, targetType, targetId, targetLabel);
        }
    }

    private void recordLoginFailure(String username, Long targetId, String errorCode) {
        if (auditLogService != null) {
            auditLogService.recordLoginFailure(username, targetId, errorCode);
        }
    }

    private void recordLoginSuccess(Long actorId, String username, String role) {
        if (auditLogService != null) {
            auditLogService.recordLoginSuccess(actorId, username, role);
        }
    }

    private void updateUserOrThrow(User user, String operation) {
        if (!updateById(user)) {
            throw new BusinessException(operation);
        }
    }

    private User getUserForUpdate(Long userId) {
        User lockedUser = getBaseMapper().selectByIdForUpdate(userId);
        return lockedUser == null ? getById(userId) : lockedUser;
    }

    private List<Long> lockEnabledAdminIds() {
        return getBaseMapper().selectEnabledAdminIdsForUpdate();
    }

    private long currentTokenVersion(User user) {
        return user == null || user.getTokenVersion() == null ? 0L : user.getTokenVersion();
    }

    private void incrementTokenVersion(User user) {
        long currentVersion = currentTokenVersion(user);
        if (currentVersion == Long.MAX_VALUE) {
            throw new BusinessException("用户会话版本已达到上限，请联系管理员");
        }
        user.setTokenVersion(currentVersion + 1);
    }

    /** 锁定活跃管理员集合，避免并发降级/停用同时留下零个管理员。 */
    private void assertNotLastActiveAdmin(Long targetUserId) {
        List<Long> adminIds = lockEnabledAdminIds();
        if (adminIds != null && adminIds.stream().anyMatch(targetUserId::equals) && adminIds.size() <= 1) {
            throw new BusinessException(409, "系统至少需要保留一个启用的管理员账号");
        }
    }

    private boolean isSetupStateInitialized() {
        if (jdbcTemplate == null) {
            return false;
        }
        Integer initialized = jdbcTemplate.queryForObject(
                "SELECT initialized FROM farm_setup_state WHERE id = 1", Integer.class);
        return Integer.valueOf(1).equals(initialized);
    }

    private void markSetupInitialized(Long userId) {
        if (jdbcTemplate == null) {
            return;
        }
        int updated = jdbcTemplate.update(
                "UPDATE farm_setup_state SET initialized = 1, initialized_by = ?, initialized_at = CURRENT_TIMESTAMP "
                        + "WHERE id = 1 AND initialized = 0",
                userId);
        if (updated != 1) {
            throw new BusinessException(409, "系统已完成初始化，请登录后由管理员创建账号");
        }
    }

    private void invalidateBootstrapTokenAfterCommit() {
        if (bootstrapTokenService == null) {
            return;
        }
        Runnable invalidator = bootstrapTokenService::invalidate;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidator.run();
                }
            });
        } else {
            invalidator.run();
        }
    }

    private void assertAdmin(Long adminId) {
        User admin = getById(adminId);
        if (admin == null) {
            throw new BusinessException("管理员账号不存在");
        }
        if (!ROLE_ADMIN.equalsIgnoreCase(admin.getRole()) || Boolean.FALSE.equals(admin.getEnabled())) {
            throw new BusinessException("当前用户无管理员权限");
        }
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? null : role.trim().toUpperCase();
        if (ROLE_ADMIN.equals(normalized) || ROLE_OPERATOR.equals(normalized) || "VIEWER".equals(normalized)) {
            return normalized;
        }
        throw new BusinessException("非法角色: " + role);
    }
}
