package com.example.farm.entity.enums;

/**
 * v2 派单模式。
 *
 * <p>后台自动派单不属于 v2；用户点击后执行的自动匹配仍然是 USER_BATCH。</p>
 */
public enum DispatchMode {
    MANUAL,
    USER_BATCH
}
