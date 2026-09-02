package com.example.farm.config;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.farm.common.api.Result;
import com.example.farm.common.utils.JwtUtils;
import com.example.farm.common.utils.LoginProtectUtil;
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
import java.util.Collections;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final LoginProtectUtil loginProtectUtil;

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
                    String role = jwt.getClaim("role").asString();

                    if (userId != null && loginProtectUtil.isUserDisabled(userId)) {
                        logger.warn("访问被拒绝：用户已被禁用，userId=" + userId);
                        renderJson(response, HttpServletResponse.SC_FORBIDDEN, 403, "用户已被禁用，请联系管理员");
                        return;
                    }

                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userId,
                            username,
                            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }

            } catch (TokenExpiredException e) {
                logger.warn("访问被拒绝：Token 已过期");
                renderJson(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "登录已过期，请重新登录");
                return;
            } catch (JWTVerificationException e) {
                logger.warn("访问被拒绝：无效 Token");
                renderJson(response, HttpServletResponse.SC_UNAUTHORIZED, 401, "无效的访问凭证，请重新登录");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void renderJson(HttpServletResponse response, int statusCode, long code, String message) throws IOException {
        response.setStatus(statusCode);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        Result<Object> result = Result.failed(code, message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
