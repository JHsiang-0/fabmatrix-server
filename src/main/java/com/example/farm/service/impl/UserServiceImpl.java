package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.api.ResultCode;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.common.utils.PasswordMigrationUtil;
import com.example.farm.entity.User;
import com.example.farm.entity.dto.ChangePasswordDTO;
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
import com.example.farm.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public LoginResultDTO login(UserLoginDTO loginDTO) {
        String username = loginDTO.getUsername();

        if (loginProtectUtil.isLocked(username)) {
            long remainingMinutes = loginProtectUtil.getRemainingLockTime(username);
            throw authenticationFailure("账号已锁定，请 " + remainingMinutes + " 分钟后重试");
        }

        User user = findByUsername(username);
        if (user == null) {
            loginProtectUtil.recordLoginFail(username);
            throw authenticationFailure("账号或密码错误");
        }

        if (loginProtectUtil.isUserDisabled(user.getId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "用户已被禁用，请联系管理员");
        }

        PasswordMigrationUtil.MigrateResult verifyResult = PasswordMigrationUtil.matchesAndMigrate(
                loginDTO.getPassword(), user.getPasswordHash());

        if (!verifyResult.isMatches()) {
            int failCount = loginProtectUtil.recordLoginFail(username);
            int remainingAttempts = 5 - failCount;
            if (remainingAttempts > 0) {
                throw authenticationFailure("账号或密码错误，还剩 " + remainingAttempts + " 次机会");
            }
            throw authenticationFailure("账号已锁定，请 15 分钟后重试");
        }

        loginProtectUtil.clearLoginFail(username);

        if (verifyResult.needMigration()) {
            user.setPasswordHash(verifyResult.getNewHash());
            updateUserOrThrow(user, "用户密码自动迁移失败");
            log.info("用户密码已自动迁移为加密存储: username={}", user.getUsername());
        }

        String token = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());
        LoginResultDTO result = new LoginResultDTO(
                token,
                jwtExpireTime / 1000,
                user.getId(),
                user.getUsername(),
                user.getRole()
        );
        result.setEmail(user.getEmail());
        result.setPhone(user.getPhone());

        log.info("用户登录成功: username={}", user.getUsername());
        return result;
    }

    @Override
    public FirstAdminSetupStatusDTO getFirstAdminSetupStatus() {
        boolean initialized = count() > 0;
        return new FirstAdminSetupStatusDTO(initialized, firstAdminSetupEnabled && !initialized);
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
            if (!setupDTO.isPasswordMatch()) {
                throw new BusinessException("两次输入的密码不一致");
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

            if (!save(admin)) {
                throw new BusinessException("管理员创建失败");
            }

            LoginResultDTO result = new LoginResultDTO(
                    JwtUtils.generateToken(admin.getId(), admin.getUsername(), admin.getRole()),
                    jwtExpireTime / 1000,
                    admin.getId(),
                    admin.getUsername(),
                    admin.getRole()
            );
            result.setEmail(admin.getEmail());
            result.setPhone(admin.getPhone());
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
        newUser.setRole(ROLE_OPERATOR);

        if (!save(newUser)) {
            throw new BusinessException("用户创建失败");
        }
        log.info("用户注册成功: username={}, id={}", newUser.getUsername(), newUser.getId());
        return newUser.getId();
    }

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordDTO changePasswordDTO) {
        if (!changePasswordDTO.isNewPasswordMatch()) {
            throw new BusinessException("两次输入的新密码不一致");
        }

        User user = getById(userId);
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
        updateUserOrThrow(user, "用户修改密码失败");
        log.info("用户修改密码成功: username={}", user.getUsername());
    }

    @Override
    @Transactional
    public void updateUserInfo(UserUpdateDTO updateDTO) {
        User user = getById(updateDTO.getId());
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

        if (StringUtils.isNotBlank(updateDTO.getRole())) {
            user.setRole(normalizeRole(updateDTO.getRole()));
        }

        updateUserOrThrow(user, "用户信息更新失败");
        log.info("用户信息更新成功: username={}", user.getUsername());
    }

    @Override
    public UserVO getCurrentUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
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

        wrapper.orderByDesc(User::getCreatedAt);
        IPage<User> result = page(page, wrapper);
        Page<UserVO> safePage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        safePage.setRecords(result.getRecords().stream().map(UserVO::from).toList());
        return safePage;
    }

    @Override
    @Transactional
    public void disableUser(Long userId, Long adminId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (userId.equals(adminId)) {
            throw new BusinessException("不能禁用当前登录账号");
        }

        assertAdmin(adminId);
        loginProtectUtil.disableUser(userId);
        loginProtectUtil.clearLoginFail(user.getUsername());
        log.info("用户已被禁用: targetUserId={}, adminId={}", userId, adminId);
    }

    @Override
    @Transactional
    public void enableUser(Long userId, Long adminId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        assertAdmin(adminId);
        loginProtectUtil.enableUser(userId);
        log.info("用户已被启用: targetUserId={}, adminId={}", userId, adminId);
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
        return new BusinessException(ResultCode.UNAUTHORIZED.getCode(), message);
    }

    private void updateUserOrThrow(User user, String operation) {
        if (!updateById(user)) {
            throw new BusinessException(operation);
        }
    }

    private void assertAdmin(Long adminId) {
        User admin = getById(adminId);
        if (admin == null) {
            throw new BusinessException("管理员账号不存在");
        }
        if (!ROLE_ADMIN.equalsIgnoreCase(admin.getRole())) {
            throw new BusinessException("当前用户无管理员权限");
        }
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? null : role.trim().toUpperCase();
        if (ROLE_ADMIN.equals(normalized) || ROLE_OPERATOR.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException("非法角色: " + role);
    }
}
