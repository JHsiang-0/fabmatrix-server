package com.example.farm.entity.enums;

/**
 * 设备当前执行文件与 Farm 平台任务的关联来源。
 */
public enum DeviceTaskSource {
    /** 设备当前没有执行打印。 */
    NONE,
    /** 设备文件与当前 Farm 任务明确匹配。 */
    FARM,
    /** 设备正在执行未由当前 Farm 任务关联的文件。 */
    EXTERNAL,
    /** 设备正在执行，但协议信息不足以安全判断来源。 */
    UNKNOWN
}
