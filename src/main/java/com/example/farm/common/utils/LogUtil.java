package com.example.farm.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * 日志工具类
 * 提供企业级标准的日志记录方法
 */
@Slf4j
public class LogUtil {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ACCESS_LOG");

    // ==================== MDC 上下文管理 ====================

    /**
     * 设置请求追踪ID
     */
    public static void setTraceId(String traceId) {
        MDC.put("traceId", traceId != null ? traceId : generateTraceId());
    }

    /**
     * 清除请求追踪ID
     */
    public static void clearTraceId() {
        MDC.remove("traceId");
    }

    /**
     * 生成追踪ID
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ==================== 访问日志 ====================

    /**
     * 记录访问日志
     */
    public static void access(String traceId, String method, String uri, String ip, String userId, int status, long duration) {
        ACCESS_LOG.info("traceId={} | 用户={} | 请求={} {} | IP={} | 状态={} | 耗时={}ms",
                traceId, userId, method, uri, ip, status, duration);
    }

    // ==================== 业务操作日志 ====================

    /**
     * 记录业务操作成功日志
     */
    public static void bizInfo(String operation, Object... params) {
        log.info("业务操作成功: {} | {}", operation, formatParams(params));
    }

    /**
     * 记录业务操作失败日志
     */
    public static void bizError(String operation, Throwable e, Object... params) {
        log.error("业务操作失败: {} | {} | 异常: {}", operation, formatParams(params), e.getMessage(), e);
    }

    // ==================== 系统启动/关闭日志 ====================

    /**
     * 记录系统启动日志
     */
    public static void startup(String component) {
        log.info("组件启动: {}", component);
    }

    /**
     * 记录系统关闭日志
     */
    public static void shutdown(String component) {
        log.info("组件停止: {}", component);
    }

    // ==================== 性能日志 ====================

    /**
     * 记录慢操作日志（超过阈值）
     */
    public static void slowOperation(String operation, long durationMs, long thresholdMs) {
        if (durationMs > thresholdMs) {
            log.warn("慢操作告警: {} 耗时 {}ms，阈值 {}ms", operation, durationMs, thresholdMs);
        }
    }

    // ==================== 安全日志 ====================

    /**
     * 记录登录成功日志
     */
    public static void loginSuccess(String username, String ip) {
        log.info("安全日志: 登录成功 | 用户={} | IP={}", username, ip);
    }

    /**
     * 记录登录失败日志
     */
    public static void loginFailed(String username, String ip, String reason) {
        log.warn("安全日志: 登录失败 | 用户={} | IP={} | 原因={}", username, ip, reason);
    }

    /**
     * 记录权限拒绝日志
     */
    public static void accessDenied(String userId, String resource, String requiredRole) {
        log.warn("安全日志: 访问拒绝 | 用户={} | 资源={} | 需要角色={}", userId, resource, requiredRole);
    }

    // ==================== 数据操作日志 ====================

    /**
     * 记录数据变更日志
     */
    public static void dataChange(String operation, String entityType, Object entityId, String changeDesc) {
        log.info("数据变更: {} | {}:{} | {}", operation, entityType, entityId, changeDesc);
    }

    // ==================== 外部调用日志 ====================

    /**
     * 记录外部API调用成功
     */
    public static void externalCallSuccess(String service, String operation, long durationMs) {
        log.debug("外部调用成功: {}.{} | 耗时={}ms", service, operation, durationMs);
    }

    /**
     * 记录外部API调用失败
     */
    public static void externalCallFailed(String service, String operation, String error, long durationMs) {
        log.error("外部调用失败: {}.{} | 耗时={}ms | 错误={}", service, operation, durationMs, error);
    }

    // ==================== 辅助方法 ====================

    private static String formatParams(Object... params) {
        if (params == null || params.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i += 2) {
            if (i > 0) sb.append(", ");
            String key = String.valueOf(params[i]);
            String value = i + 1 < params.length ? String.valueOf(params[i + 1]) : "null";
            // 敏感信息脱敏
            if (isSensitiveKey(key)) {
                value = maskSensitive(value);
            }
            sb.append(key).append("=").append(value);
        }
        return sb.toString();
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || 
               lower.contains("token") || lower.contains("key") ||
               lower.contains("credential") || lower.contains("auth");
    }

    private static String maskSensitive(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }
}
