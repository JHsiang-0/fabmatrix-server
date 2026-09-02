package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.entity.User;
import com.example.farm.entity.dto.ChangePasswordDTO;
import com.example.farm.entity.dto.UserRegisterDTO;
import com.example.farm.entity.dto.UserUpdateDTO;
import com.example.farm.entity.dto.UserLoginDTO;
import com.example.farm.mapper.UserMapper;
import com.example.farm.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAuthenticationTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LoginProtectUtil loginProtectUtil;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void injectMyBatisMapper() {
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);
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
    void loginFailsWhenLegacyPasswordMigrationAffectsNoRows() {
        User user = user(1L, "admin", "Admin123");
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);
        when(loginProtectUtil.isUserDisabled(1L)).thenReturn(false);
        when(userMapper.updateById(any(User.class))).thenReturn(0);

        assertThatThrownBy(() -> userService.login(login("admin", "Admin123")))
                .hasMessage("用户密码自动迁移失败");
        verify(userMapper).updateById(user);
    }

    @Test
    void disabledAccountReturnsForbiddenBusinessCode() {
        User user = user(1L, "admin", "$2a$10$hash");
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);
        when(loginProtectUtil.isUserDisabled(1L)).thenReturn(true);

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
