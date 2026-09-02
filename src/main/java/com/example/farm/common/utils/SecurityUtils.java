package com.example.farm.common.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class SecurityUtils {

    /**
     * 获取当前登录的老板(租户) ID
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("安全拦截：当前未登录或登录已过期");
        }

        Object principal = authentication.getPrincipal();

        // 因为我们在 Filter 里直接 new UsernamePasswordAuthenticationToken(userId, ...)
        // 所以这里掏出来的一定是 Long 类型的 userId
        if (principal instanceof Long) {
            return (Long) principal;
        }

        throw new RuntimeException("系统异常：无法获取当前租户身份");
    }
}