package com.example.farm.config;

import com.example.farm.common.utils.LogUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
public class RequestTraceLogFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_ATTRIBUTE = "farm.traceId";
    private static final Set<String> IGNORE_PREFIX = Set.of("/swagger-ui", "/v3/api-docs");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        long start = System.currentTimeMillis();
        String uri = request.getRequestURI();

        LogUtil.setTraceId(traceId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!shouldSkip(uri)) {
                LogUtil.access(
                        traceId,
                        request.getMethod(),
                        uri,
                        resolveClientIp(request),
                        resolveCurrentUserId(),
                        response.getStatus(),
                        System.currentTimeMillis() - start
                );
            }
            LogUtil.clearTraceId();
        }
    }

    private boolean shouldSkip(String uri) {
        if (!StringUtils.hasText(uri)) {
            return false;
        }
        for (String prefix : IGNORE_PREFIX) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String resolveTraceId(String requestTraceId) {
        if (StringUtils.hasText(requestTraceId) && requestTraceId.length() <= 64) {
            return requestTraceId;
        }
        return LogUtil.generateTraceId();
    }

    private String resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null) {
            return "anonymous";
        }
        return String.valueOf(authentication.getPrincipal());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            int idx = xff.indexOf(',');
            return idx > 0 ? xff.substring(0, idx).trim() : xff.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
