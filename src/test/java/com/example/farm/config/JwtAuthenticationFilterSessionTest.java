package com.example.farm.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.entity.User;
import com.example.farm.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterSessionTest {

    private static final String SECRET = "jwt-session-filter-test-secret";

    private final LoginProtectUtil loginProtectUtil = Mockito.mock(LoginProtectUtil.class);
    private final UserMapper userMapper = Mockito.mock(UserMapper.class);
    private TestJwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_SECRET_KEY", SECRET);
        ReflectionTestUtils.setField(JwtUtils.class, "STATIC_EXPIRE_TIME", 60_000L);
        filter = new TestJwtAuthenticationFilter(new ObjectMapper(), loginProtectUtil);
        ReflectionTestUtils.setField(filter, "userMapper", userMapper);
    }

    @AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsTokenAfterRoleChange() throws Exception {
        User current = user(1L, "operator", "OPERATOR", 1L, true);
        when(userMapper.selectById(1L)).thenReturn(current);

        MockHttpServletResponse response = execute(token(1L, "operator", "ADMIN", 1L));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录状态已失效");
    }

    @Test
    void rejectsTokenAfterPasswordChange() throws Exception {
        User current = user(1L, "operator", "OPERATOR", 2L, true);
        when(userMapper.selectById(1L)).thenReturn(current);

        MockHttpServletResponse response = execute(token(1L, "operator", "OPERATOR", 1L));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录状态已失效");
    }

    @Test
    void rejectsTokenAfterAccountDisable() throws Exception {
        User current = user(1L, "operator", "OPERATOR", 1L, false);
        when(userMapper.selectById(1L)).thenReturn(current);

        MockHttpServletResponse response = execute(token(1L, "operator", "OPERATOR", 1L));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录状态已失效");
    }

    @Test
    void acceptsTokenMatchingCurrentRoleAndVersion() throws Exception {
        User current = user(1L, "operator", "OPERATOR", 3L, true);
        when(userMapper.selectById(1L)).thenReturn(current);
        MockHttpServletRequest request = request(token(1L, "operator", "OPERATOR", 3L));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.filterForTest(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }

    private MockHttpServletResponse execute(String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.filterForTest(request(token), response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private String token(Long userId, String username, String role, Long tokenVersion) {
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withClaim("role", role)
                .withClaim("tokenVersion", tokenVersion)
                .withExpiresAt(new Date(System.currentTimeMillis() + 60_000L))
                .sign(Algorithm.HMAC256(SECRET));
    }

    @Test
    void forcedPasswordChangeAllowsOnlyIdentityOwnPasswordAndLogout() throws Exception {
        User current = user(1L, "operator", "OPERATOR", 0L, true);
        current.setMustChangePassword(true);
        when(userMapper.selectById(1L)).thenReturn(current);
        String token = token(1L, "operator", "OPERATOR", 0L);
        for (String path : java.util.List.of("/api/v1/printers/page", "/api/v1/auth/sessions", "/api/v1/auth/2/change-password")) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            MockHttpServletRequest request = request(token); request.setMethod("POST"); request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.filterForTest(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("4031");
        }
        for (String path : java.util.List.of("/api/v1/auth/1/change-password", "/api/v1/auth/logout")) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            MockHttpServletRequest request = request(token); request.setMethod("POST"); request.setRequestURI(path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.filterForTest(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void revokedSessionIsRejectedBeforeController() throws Exception {
        var sessions = Mockito.mock(com.example.farm.service.LoginSessionService.class);
        ReflectionTestUtils.setField(filter, "loginSessionService", sessions);
        assertThat(execute(token(1L, "operator", "OPERATOR", 0L)).getStatus()).isEqualTo(401);
    }

    private User user(Long id, String username, String role, Long tokenVersion, boolean enabled) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setTokenVersion(tokenVersion);
        user.setEnabled(enabled);
        return user;
    }

    private static class TestJwtAuthenticationFilter extends JwtAuthenticationFilter {
        private TestJwtAuthenticationFilter(ObjectMapper objectMapper, LoginProtectUtil loginProtectUtil) {
            super(objectMapper, loginProtectUtil);
        }

        private void filterForTest(MockHttpServletRequest request, MockHttpServletResponse response,
                                   FilterChain chain) throws Exception {
            doFilterInternal(request, response, chain);
        }
    }
}
