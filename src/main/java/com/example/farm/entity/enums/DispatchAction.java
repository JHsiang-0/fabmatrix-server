package com.example.farm.entity.enums;

/** 批量计划确认后的执行动作。 */
public enum DispatchAction {
    /** 上传到打印机，但不启动打印。 */
    UPLOAD_ONLY,
    /** 创建/绑定任务并进入 Farm 队列，不调用设备启动。 */
    QUEUE,
    /** 进入逐项安全确认后才能启动，不允许批量接口绕过安全确认。 */
    START_AFTER_CONFIRM
}
