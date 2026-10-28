package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.entity.User;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.dto.ChangePasswordDTO;
import com.example.farm.entity.dto.UserRegisterDTO;
import com.example.farm.entity.dto.UserUpdateDTO;
import com.example.farm.entity.dto.UserLoginDTO;
import com.example.farm.entity.dto.LoginResultDTO;
import com.example.farm.mapper.UserMapper;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.BootstrapTokenService;
import com.example.farm.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class UserAuthenticationTest {

    @Test
    void adminResetUsesHashAndForcesPasswordChangeAndRevokesSessions() {
        var sessions = mock(com.example.farm.service.LoginSessionService.class);
        ReflectionTestUtils.setField(userService, "loginSessionService", sessions);
        User admin = user(9L, "admin", "hash"); admin.setRole("ADMIN");
        User worker = user(2L, "worker", "old-hash"); worker.setRole("OPERATOR");
        when(userMapper.selectById(9L)).thenReturn(admin);
        when(userMapper.selectByIdForUpdate(2L)).thenReturn(worker);
        when(userMapper.updateById(worker)).thenReturn(1);
        when(passwordEncoder.encode("Temporary1")).thenReturn("bcrypt-temporary");
        var request = new com.example.farm.entity.dto.ResetPasswordDTO();
        request.setPassword("Temporary1"); request.setConfirmPassword("Temporary1");
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                9L, "admin", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            userService.resetPassword(2L, request);
            assertThat(worker.getPasswordHash()).isEqualTo("bcrypt-temporary");
            assertThat(worker.getMustChangePassword()).isTrue();
            assertThat(worker.getTokenVersion()).isEqualTo(1L);
            verify(sessions).revokeAll(2L, null);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    @Test
    void changingTemporaryPasswordClearsFlag() {
        User worker = user(2L, "worker", "$2a$10$stored-hash");
        worker.setMustChangePassword(true);
        when(userMapper.selectById(2L)).thenReturn(worker);
        when(passwordEncoder.matches("OldPass1", worker.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("NewPass1")).thenReturn("new-hash");
        when(userMapper.updateById(worker)).thenReturn(1);
        ChangePasswordDTO change = new ChangePasswordDTO();
        change.setOldPassword("OldPass1"); change.setNewPassword("NewPass1"); change.setConfirmPassword("NewPass1");
        userService.changePassword(2L, change);
        assertThat(worker.getMustChangePassword()).isFalse();
        assertThat(worker.getTokenVersion()).isEqualTo(1L);
    }

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LoginProtectUtil loginProtectUtil;
    @Mock
    private PrintFileMapper printFileMapper;
    @Mock
    private BootstrapTokenService bootstrapTokenService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void injectMyBatisMapper() {
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
        ReflectionTestUtils.setField(userService, "printFileMapper", printFileMapper);
        ReflectionTestUtils.setField(userService, "bootstrapTokenService", bootstrapTokenService);
        ReflectionTestUtils.setField(userService, "jdbcTemplate", jdbcTemplate);
    }

    @Test
    void lockedAccountReturnsUnauthorizedBusinessCode() {
        when(loginProtectUtil.isLocked("admin")).thenReturn(true);
        when(loginProtectUtil.getRemainingLockTime("admin")).thenReturn(12L);

        BusinessException error = catchBusinessException(() -> userService.login(login("admin", "Admin123")));

        assertThat(error.getCode()).isEqualTo(401);
        assertThat(error).hasMessage("账号已锁定，请 12 分钟后重试");
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void wrongPasswordReturnsUnauthorizedBusinessCode() {
        User user = user(1L, "admin", "Correct123");
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);
        when(loginProtectUtil.recordLoginFail("admin")).thenReturn(1);

        BusinessException error = catchBusinessException(() -> userService.login(login("admin", "Wrong123")));

        assertThat(error.getCode()).isEqualTo(401);
        assertThat(error).hasMessage("账号或密码错误，还剩 4 次机会");
        verify(loginProtectUtil).recordLoginFail("admin");
    }

    @Test
    void loginTokenContainsTheLockedCurrentSessionVersion() {
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_SECRET_KEY", "login-session-version-secret");
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_EXPIRE_TIME", 60_000L);
        ReflectionTestUtils.setField(userService, "jwtExpireTime", 60_000L);
        User user = user(1L, "operator", "Operator123");
        user.setRole("OPERATOR");
        user.setTokenVersion(7L);
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        LoginResultDTO result = userService.login(login("operator", "Operator123"));

        assertThat(JwtUtils.verifyToken(result.getToken()).getClaim("tokenVersion").asLong()).isEqualTo(7L);
    }

    @Test
    void loginFailsWhenLegacyPasswordMigrationAffectsNoRows() {
        User user = user(1L, "admin", "Admin123");
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        assertThatThrownBy(() -> userService.login(login("admin", "Admin123")))
                .hasMessage("用户密码自动迁移失败");
        verify(userMapper).updateById(user);
    }

    @Test
    void disabledAccountReturnsForbiddenBusinessCode() {
        User user = user(1L, "admin", "$2a$10$hash");
        user.setEnabled(false);
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);

        BusinessException error = catchBusinessException(() -> userService.login(login("admin", "Admin123")));

        assertThat(error.getCode()).isEqualTo(403);
        assertThat(error).hasMessage("用户已被禁用，请联系管理员");
        verify(loginProtectUtil, never()).recordLoginFail("admin");
    }

    @Test
    void changingPasswordFailsWhenDatabaseUpdateAffectsNoRows() {
        User user = user(1L, "operator", "$2a$10$stored-hash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("OldPass1", "$2a$10$stored-hash")).thenReturn(true);
        when(passwordEncoder.matches("NewPass1", "$2a$10$stored-hash")).thenReturn(false);
        when(passwordEncoder.encode("NewPass1")).thenReturn("new-hash");
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword("OldPass1");
        request.setNewPassword("NewPass1");
        request.setConfirmPassword("NewPass1");

        assertThatThrownBy(() -> userService.changePassword(1L, request))
                .hasMessage("用户修改密码失败");
    }

    @Test
    void changingPasswordIncrementsTokenVersion() {
        User user = user(1L, "operator", "OldPass1");
        user.setRole("OPERATOR");
        user.setTokenVersion(4L);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(user);
        when(passwordEncoder.encode("NewPass1")).thenReturn("new-hash");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword("OldPass1");
        request.setNewPassword("NewPass1");
        request.setConfirmPassword("NewPass1");

        userService.changePassword(1L, request);

        assertThat(user.getTokenVersion()).isEqualTo(5L);
        verify(userMapper).updateById(user);
    }

    @Test
    void changingPasswordRejectsMismatchedPasswordsBeforeReadingUser() {
        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword("OldPass1");
        request.setNewPassword("NewPass1");
        request.setConfirmPassword("Different1");

        assertThatThrownBy(() -> userService.changePassword(1L, request))
                .hasMessage("两次输入的新密码不一致");
        verify(userMapper, never()).selectById(1L);
    }

    @Test
    void updatingUserInfoFailsWhenDatabaseUpdateAffectsNoRows() {
        User user = user(1L, "operator", "stored-hash");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setId(1L);
        request.setPhone("13800138000");

        assertThatThrownBy(() -> userService.updateUserInfo(request))
                .hasMessage("用户信息更新失败");
    }

    @Test
    void changingRoleIncrementsTokenVersion() {
        User user = user(1L, "admin", "stored-hash");
        user.setTokenVersion(2L);
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setId(1L);
        request.setRole("OPERATOR");

        userService.updateUserInfo(request);

        assertThat(user.getRole()).isEqualTo("OPERATOR");
        assertThat(user.getTokenVersion()).isEqualTo(3L);
        verify(userMapper).updateById(user);
        var lockOrder = inOrder(userMapper);
        lockOrder.verify(userMapper).selectEnabledAdminIdsForUpdate();
        lockOrder.verify(userMapper).selectByIdForUpdate(1L);
    }

    @Test
    void cannotDemoteTheLastEnabledAdmin() {
        User user = user(1L, "admin", "stored-hash");
        when(userMapper.selectByIdForUpdate(1L)).thenReturn(user);
        when(userMapper.selectEnabledAdminIdsForUpdate()).thenReturn(java.util.List.of(1L));

        UserUpdateDTO request = new UserUpdateDTO();
        request.setId(1L);
        request.setRole("OPERATOR");

        assertThatThrownBy(() -> userService.updateUserInfo(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("系统至少需要保留一个启用的管理员账号")
                .extracting("code")
                .isEqualTo(409L);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void cannotDisableTheLastEnabledAdmin() {
        User target = user(2L, "target-admin", "stored-hash");
        User actor = user(1L, "admin", "stored-hash");
        when(userMapper.selectByIdForUpdate(2L)).thenReturn(target);
        when(userMapper.selectById(1L)).thenReturn(actor);
        when(userMapper.selectEnabledAdminIdsForUpdate()).thenReturn(java.util.List.of(2L));

        assertThatThrownBy(() -> userService.disableUser(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("系统至少需要保留一个启用的管理员账号")
                .extracting("code")
                .isEqualTo(409L);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void creatingUserFailsWhenDatabaseInsertAffectsNoRows() {
        when(passwordEncoder.encode("Admin123")).thenReturn("encoded");
        when(userMapper.insert(any(User.class))).thenReturn(0);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("operator");
        request.setPassword("Admin123");
        request.setConfirmPassword("Admin123");

        assertThatThrownBy(() -> userService.register(request))
                .hasMessage("用户创建失败");
    }

    @Test
    void creatingUserRejectsMismatchedPasswords() {
        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("operator");
        request.setPassword("123456");
        request.setConfirmPassword("654321");

        assertThatThrownBy(() -> userService.register(request))
                .hasMessage("两次输入的密码不一致");
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerCreatesDefaultFolderNamedAfterUsername() {
        when(passwordEncoder.encode("Admin123")).thenReturn("encoded");
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(2L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(printFileMapper.insert(any(PrintFile.class))).thenReturn(1);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("operator");
        request.setPassword("Admin123");
        request.setConfirmPassword("Admin123");

        assertThat(userService.register(request)).isEqualTo(2L);

        var insertedFolder = forClass(PrintFile.class);
        verify(printFileMapper).insert(insertedFolder.capture());
        assertThat(insertedFolder.getValue().getOriginalName()).isEqualTo("operator的文件");
        assertThat(insertedFolder.getValue().getIsDefaultFolder()).isTrue();
        assertThat(insertedFolder.getValue().getIsFolder()).isTrue();
        assertThat(insertedFolder.getValue().getParentId()).isNull();
        assertThat(insertedFolder.getValue().getUserId()).isEqualTo(2L);
    }

    @Test
    void firstAdminSetupCreatesAdminAndReturnsToken() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", true);
        ReflectionTestUtils.setField(userService, "jwtExpireTime", 604800000L);
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_SECRET_KEY", "test-secret-key");
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_EXPIRE_TIME", 604800000L);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(passwordEncoder.encode("Admin123")).thenReturn("encoded-admin-password");
        doAnswer(invocation -> {
            User admin = invocation.getArgument(0);
            admin.setId(1L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(printFileMapper.insert(any(PrintFile.class))).thenReturn(1);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("admin");
        request.setPassword("Admin123");
        request.setConfirmPassword("Admin123");
        request.setBootstrapToken("valid-bootstrap-token");
        when(bootstrapTokenService.matches("valid-bootstrap-token")).thenReturn(true);

        var result = userService.setupFirstAdmin(request);

        assertThat(result.getToken()).isNotBlank();
        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getRole()).isEqualTo("ADMIN");
        var insertedUser = forClass(User.class);
        verify(userMapper).insert(insertedUser.capture());
        assertThat(insertedUser.getValue().getUsername()).isEqualTo("admin");
        assertThat(insertedUser.getValue().getRole()).isEqualTo("ADMIN");
        assertThat(insertedUser.getValue().getPasswordHash()).isEqualTo("encoded-admin-password");
        var insertedFolder = forClass(PrintFile.class);
        verify(printFileMapper).insert(insertedFolder.capture());
        assertThat(insertedFolder.getValue().getOriginalName()).isEqualTo("admin的文件");
        assertThat(insertedFolder.getValue().getIsDefaultFolder()).isTrue();
        assertThat(insertedFolder.getValue().getIsFolder()).isTrue();
        assertThat(insertedFolder.getValue().getParentId()).isNull();
        assertThat(insertedFolder.getValue().getUserId()).isEqualTo(1L);
        verify(bootstrapTokenService).invalidate();
    }

    @Test
    void disabledFirstAdminSetupStatusUsesUserCountWithoutReadingSetupLatch() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", false);
        when(userMapper.selectCount(any())).thenReturn(1L);
        JdbcTemplate unavailableLatch = mock(JdbcTemplate.class, invocation -> {
            if ("queryForObject".equals(invocation.getMethod().getName())) {
                throw new AssertionError("disabled setup must not query the Local setup latch");
            }
            return null;
        });
        ReflectionTestUtils.setField(userService, "jdbcTemplate", unavailableLatch);

        var status = userService.getFirstAdminSetupStatus();

        assertThat(status.isInitialized()).isTrue();
        assertThat(status.isSetupAvailable()).isFalse();
        verify(userMapper).selectCount(any());
        verifyNoInteractions(unavailableLatch);
        verifyNoInteractions(bootstrapTokenService);
    }

    @Test
    void disabledFirstAdminSetupStatusIsNotInitializedWithoutUsers() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", false);
        when(userMapper.selectCount(any())).thenReturn(0L);

        var status = userService.getFirstAdminSetupStatus();

        assertThat(status.isInitialized()).isFalse();
        assertThat(status.isSetupAvailable()).isFalse();
        verify(userMapper).selectCount(any());
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(bootstrapTokenService);
    }

    @Test
    void enabledFirstAdminSetupStatusReadsSetupLatchAndInvalidatesBootstrapToken() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", true);
        when(jdbcTemplate.queryForObject("SELECT initialized FROM farm_setup_state WHERE id = 1", Integer.class))
                .thenReturn(1);

        var status = userService.getFirstAdminSetupStatus();

        assertThat(status.isInitialized()).isTrue();
        assertThat(status.isSetupAvailable()).isFalse();
        verify(jdbcTemplate).queryForObject("SELECT initialized FROM farm_setup_state WHERE id = 1", Integer.class);
        verify(bootstrapTokenService).invalidate();
    }

    @Test
    void enabledFirstAdminSetupStatusRemainsAvailableBeforeInitialization() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", true);
        when(jdbcTemplate.queryForObject("SELECT initialized FROM farm_setup_state WHERE id = 1", Integer.class))
                .thenReturn(0);
        when(userMapper.selectCount(any())).thenReturn(0L);

        var status = userService.getFirstAdminSetupStatus();

        assertThat(status.isInitialized()).isFalse();
        assertThat(status.isSetupAvailable()).isTrue();
        verify(jdbcTemplate).queryForObject("SELECT initialized FROM farm_setup_state WHERE id = 1", Integer.class);
        verify(userMapper).selectCount(any());
        verify(bootstrapTokenService, never()).invalidate();
    }

    @Test
    void firstAdminSetupRejectsMissingOrInvalidBootstrapToken() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", true);
        when(bootstrapTokenService.matches("invalid-token")).thenReturn(false);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("admin");
        request.setPassword("Admin123");
        request.setConfirmPassword("Admin123");
        request.setBootstrapToken("invalid-token");

        assertThatThrownBy(() -> userService.setupFirstAdmin(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("只能使用服务器本机提供的有效初始化凭据")
                .extracting("code")
                .isEqualTo(403L);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void firstAdminSetupRejectsWhenAUserAlreadyExists() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", true);
        when(userMapper.selectCount(any())).thenReturn(1L);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("admin");
        request.setPassword("Admin123");
        request.setConfirmPassword("Admin123");

        assertThatThrownBy(() -> userService.setupFirstAdmin(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("系统已完成初始化，请登录后由管理员创建账号")
                .extracting("code")
                .isEqualTo(409L);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void firstAdminSetupRejectsMismatchedPasswordsBeforeCreatingUser() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", true);
        when(userMapper.selectCount(any())).thenReturn(0L);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("admin");
        request.setPassword("123456");
        request.setConfirmPassword("654321");

        assertThatThrownBy(() -> userService.setupFirstAdmin(request))
                .hasMessage("两次输入的密码不一致");
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void firstAdminSetupIsDisabledOutsideLocalEdition() {
        ReflectionTestUtils.setField(userService, "firstAdminSetupEnabled", false);

        UserRegisterDTO request = new UserRegisterDTO();
        request.setUsername("admin");
        request.setPassword("Admin123");
        request.setConfirmPassword("Admin123");

        assertThatThrownBy(() -> userService.setupFirstAdmin(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("首次管理员初始化未开启")
                .extracting("code")
                .isEqualTo(404L);
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void rejectsMissingUserQuery() {
        assertThatThrownBy(() -> userService.pageUsers(null))
                .hasMessage("用户查询参数不能为空")
                .extracting("code")
                .isEqualTo(400L);
    }

    private UserLoginDTO login(String username, String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return dto;
    }

    private User user(Long id, String username, String passwordHash) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setRole("ADMIN");
        return user;
    }

    private BusinessException catchBusinessException(Runnable action) {
        try {
            action.run();
        } catch (BusinessException error) {
            return error;
        }
        throw new AssertionError("expected BusinessException");
    }
}
