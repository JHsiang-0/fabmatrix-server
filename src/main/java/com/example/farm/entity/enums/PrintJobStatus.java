package com.example.farm.entity.enums;

import com.example.farm.common.exception.BusinessException;

import java.util.Locale;
import java.util.Set;

/**
 * 打印任务状态及其合法流转规则。
 *
 * <p>数据库历史数据中的 PENDING、MANUAL 和 CANCELED 只作为兼容值读取，
 * 新业务代码和新数据统一使用本枚举定义的状态。</p>
 */
public enum PrintJobStatus {
    QUEUED,
    ASSIGNED,
    UPLOADING,
    READY,
    PRINTING,
    PAUSED,
    RECONCILING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 历史数据库中的等待状态。 */
    public static final String LEGACY_PENDING = "PENDING";
    /** 旧脚本曾使用的手动任务状态。 */
    public static final String LEGACY_MANUAL = "MANUAL";
    /** 英式拼写统一前的历史状态。 */
    public static final String LEGACY_CANCELED = "CANCELED";

    private static final Set<String> QUEUED_STORAGE_VALUES = Set.of(
            QUEUED.name(), LEGACY_PENDING, LEGACY_MANUAL);

    /**
     * 将数据库中的历史状态转换成当前对外状态。
     */
    public static String normalize(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case LEGACY_PENDING, LEGACY_MANUAL -> QUEUED.name();
            case LEGACY_CANCELED -> CANCELLED.name();
            default -> normalized;
        };
    }

    /**
     * 判断状态是否属于当前冻结的状态集合。
     */
    public static boolean isSupported(String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return false;
        }
        try {
            valueOf(normalized);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 返回查询队列时需要兼容的数据库存储值。
     */
    public static Set<String> queuedStorageValues() {
        return QUEUED_STORAGE_VALUES;
    }

    /**
     * 校验任务状态转换。非法转换统一使用业务码 422。
     */
    public static void requireTransition(String currentStatus, PrintJobStatus targetStatus) {
        String current = normalize(currentStatus);
        if (current == null || targetStatus == null || !isAllowed(current, targetStatus.name())) {
            throw new BusinessException(422,
                    "任务状态不允许从 [" + currentStatus + "] 转换为 ["
                            + (targetStatus == null ? null : targetStatus.name()) + "]");
        }
    }

    private static boolean isAllowed(String current, String target) {
        return switch (current) {
            case "QUEUED" -> target.equals("ASSIGNED") || target.equals("CANCELLED");
            case "ASSIGNED" -> target.equals("UPLOADING") || target.equals("READY") || target.equals("PRINTING")
                    || target.equals("QUEUED")
                    || target.equals("CANCELLED");
            case "UPLOADING" -> target.equals("READY") || target.equals("PRINTING")
                    || target.equals("FAILED") || target.equals("CANCELLED")
                    || target.equals("RECONCILING");
            case "READY" -> target.equals("PRINTING") || target.equals("QUEUED")
                    || target.equals("CANCELLED");
            case "PRINTING" -> target.equals("PAUSED") || target.equals("COMPLETED")
                    || target.equals("FAILED") || target.equals("CANCELLED")
                    || target.equals("RECONCILING");
            case "PAUSED" -> target.equals("PRINTING") || target.equals("CANCELLED")
                    || target.equals("RECONCILING");
            case "RECONCILING" -> target.equals("COMPLETED") || target.equals("FAILED")
                    || target.equals("CANCELLED") || target.equals("QUEUED");
            case "FAILED" -> target.equals("QUEUED");
            case "COMPLETED", "CANCELLED" -> false;
            default -> false;
        };
    }
}
