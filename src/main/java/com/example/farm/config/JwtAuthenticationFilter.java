package com.example.farm.config;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.farm.common.api.Result;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
import com.example.farm.entity.User;
import com.example.farm.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final LoginProtectUtil loginProtectUtil;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserMapper userMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.farm.service.LoginSessionService loginSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            try {
                DecodedJWT jwt = JwtUtils.verifyToken(token);

                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    Long userId = jwt.getClaim("userId").asLong();
                    String username = jwt.getClaim("username").asString();
                    String role = normalizeRole(jwt.getClaim("role").asString());
                    Long tokenVersion = readTokenVersion(jwt);
                    String sessionId = jwt.getClaim("sessionId").asString();
                    if (userId == null || com.example.farm.common.utils.RolePermissions.forRole(role).isEmpty()) {
                        renderJson(request, response, 401, 401, "登录身份无效");
                        return;
                    }
                    request.setAttribute("loginSessionId", sessionId);

                    if (userId != null) {
                        try {
                            if (loginProtectUtil.isUserDisabled(userId)) {
                                logger.warn("访问被拒绝：用户已被禁用，userId=" + userId);
                                renderJson(request, response, HttpServletResponse.SC_FORBIDDEN, 403, "用户已被禁用，请联系管理员");
                                return;
                            }
                        } catch (RuntimeException exception) {
                            logger.warn("访问被拒绝：用户状态校验不可用，userId=" + userId, exception);
                            renderJson(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 5001,
                                    "用户状态暂时无法确认，请稍后重试");
                            return;
                        }

                        if (loginSessionService != null) {
                            try {
                                if (!loginSessionService.valid(sessionId, userId, tokenVersion)) {
                                    renderJson(request, response, 401, 401, "登录会话已退出，请重新登录");
                                    return;
                                }
                            } catch (RuntimeException exception) {
                                renderJson(request, response, 503, 5001, "会话状态暂时无法确认");
                                return;
                            }
                        }

                        if (userMapper != null) {
                            User currentUser;
                            try {
                                currentUser = userMapper.selectById(userId);
                            } catch (RuntimeException exception) {
                                logger.warn("访问被拒绝：用户会话状态校验不可用，userId=" + userId, exception);
                                renderJson(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, 5001,
                                        "用户状态暂时无法确认，请稍后重试");
                                return;
                            }
                            if (!isCurrentSessionValid(currentUser, username, role, tokenVersion)) {
                                renderJson(request, response, HttpServletResponse.SC_UNAUTHORIZED, 401,
                                        "登录状态已失效，请重新登录");
                                return;
                            }
                            if (Boolean.TRUE.equals(currentUser.getMustChangePassword())
                                    && !allowedDuringPasswordChange(request, userId)) {
                                renderJson(request, response, 403, 4031, "请先修改临时密码");
                                return;
                            }
                        }
                    }

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userId,
                            username,
                            java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of(new SimpleGrantedAuthority("ROLE_" + role)),
                                    com.example.farm.common.utils.RolePermissions.forRole(role).stream()
                                            .map(SimpleGrantedAuthority::new)).toList()
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }

            } catch (TokenExpiredException e) {
                logger.warn("访问被拒绝：Token 已过期");
                renderJson(request, response, HttpServletResponse.SC_UNAUTHORIZED, 401, "登录已过期，请重新登录");
                return;
            } catch (JWTVerificationException e) {
                logger.warn("访问被拒绝：无效 Token");
                renderJson(request, response, HttpServletResponse.SC_UNAUTHORIZED, 401, "无效的访问凭证，请重新登录");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private Long readTokenVersion(DecodedJWT jwt) {
        Long tokenVersion = jwt.getClaim("tokenVersion").asLong();
        return tokenVersion == null ? 0L : tokenVersion;
    }

    private boolean allowedDuringPasswordChange(HttpServletRequest request, Long userId) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ("GET".equals(request.getMethod()) && path.equals("/api/v1/auth/me"))
                || ("POST".equals(request.getMethod()) && (path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/auth/" + userId + "/change-password")));
    }

    private String normalizeRole(String role) {
        return role == null ? null : role.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private boolean isCurrentSessionValid(User user, String username, String role, Long tokenVersion) {
        if (user == null || Boolean.FALSE.equals(user.getEnabled()) || role == null || username == null) {
            return false;
        }
        long currentVersion = user.getTokenVersion() == null ? 0L : user.getTokenVersion();
        return username.equals(user.getUsername())
                && role.equalsIgnoreCase(user.getRole())
                && currentVersion == tokenVersion;
    }

    private void renderJson(HttpServletRequest request, HttpServletResponse response, int statusCode,
                            long code, String message) throws IOException {
        response.setStatus(statusCode);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        Result<Object> result = Result.failed(code, message)
                .withTraceId((String) request.getAttribute(RequestTraceLogFilter.TRACE_ID_ATTRIBUTE));
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
