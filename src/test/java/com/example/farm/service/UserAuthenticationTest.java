package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.entity.User;
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
    void disabledAccountReturnsForbiddenBusinessCode() {
        User user = user(1L, "admin", "$2a$10$hash");
        when(userMapper.selectOne(any(), eq(true))).thenReturn(user);
        when(loginProtectUtil.isUserDisabled(1L)).thenReturn(true);

        BusinessException error = catchBusinessException(() -> userService.login(login("admin", "Admin123")));

        assertThat(error.getCode()).isEqualTo(403);
        assertThat(error).hasMessage("用户已被禁用，请联系管理员");
        verify(loginProtectUtil, never()).recordLoginFail("admin");
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
