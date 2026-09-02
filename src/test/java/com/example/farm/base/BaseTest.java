package com.example.farm.base;

import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

/**
 * 单元测试基类
 * 提供常用的 Mock 配置
 */
public class BaseTest {

    protected static final Long TEST_USER_ID = 1L;
    protected static final String TEST_USERNAME = "testuser";
    protected static final String TEST_ROLE = "ADMIN";

    @BeforeEach
    void setUpSecurityContext() {
        // 清理安全上下文
        SecurityContextHolder.clearContext();
    }

    /**
     * 设置模拟的安全上下文（普通用户）
     */
    protected void mockSecurityContext() {
        mockSecurityContext(TEST_USER_ID, TEST_USERNAME, TEST_ROLE);
    }

    /**
     * 设置模拟的安全上下文
     *
     * @param userId   用户ID
     * @param username 用户名
     * @param role     角色
     */
    protected void mockSecurityContext(Long userId, String username, String role) {
        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + role)
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                authorities
        );
        authentication.setDetails(username);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * 清理安全上下文
     */
    protected void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 使用 Mocktio 静态 Mock 一个工具类
     *
     * @param clazz 要 Mock 的类
     * @param <T>   类类型
     * @return MockedStatic 对象，使用完后需要调用 close()
     */
    protected <T> MockedStatic<T> mockStatic(Class<T> clazz) {
        return Mockito.mockStatic(clazz);
    }
}