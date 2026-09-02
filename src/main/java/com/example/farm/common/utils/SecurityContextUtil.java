package com.example.farm.common.utils;

import com.example.farm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具类
 * 用于获取当前登录用户信息
 */
@Slf4j
public class SecurityContextUtil {

    /**
     * 获取当前登录用户ID
     *
     * @return 用户ID
     * @throws BusinessException 未登录时抛出异常
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("获取当前用户ID失败：用户未认证");
            throw new BusinessException("用户未登录");
        }
        
        Object principal = authentication.getPrincipal();
        
        // 如果 principal 是 Long 类型（用户ID）
        if (principal instanceof Long) {
            return (Long) principal;
        }
        
        // 如果 principal 是 String 类型，尝试转换
        if (principal instanceof String) {
            try {
                return Long.valueOf((String) principal);
            } catch (NumberFormatException e) {
                log.error("转换用户ID失败: {}", principal);
                throw new BusinessException("获取用户信息失败");
            }
        }
        
        log.warn("未知的 principal 类型: {}", principal.getClass());
        throw new BusinessException("获取用户信息失败");
    }

    /**
     * 获取当前登录用户ID（可选，未登录返回null）
     *
     * @return 用户ID，未登录返回null
     */
    public static Long getCurrentUserIdNullable() {
        try {
            return getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前登录用户名
     *
     * @return 用户名
     */
    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        
        return authentication.getName();
    }

    /**
     * 检查是否已登录
     *
     * @return true-已登录
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }

    /**
     * 获取当前登录用户角色（去掉 ROLE_ 前缀）。
     *
     * @return 角色名，如 ADMIN / OPERATOR；获取失败返回 null
     */
    public static String getCurrentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getAuthorities() == null || authentication.getAuthorities().isEmpty()) {
            return null;
        }
        GrantedAuthority first = authentication.getAuthorities().iterator().next();
        if (first == null || first.getAuthority() == null) {
            return null;
        }
        String authority = first.getAuthority();
        return authority.startsWith("ROLE_") ? authority.substring(5) : authority;
    }
}
