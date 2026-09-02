package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 接收 Moonraker 打印机实时状态的 DTO
 * <p>
 * 统一状态机说明：
 * 通过 calculateUnifiedStatus() 方法将 systemState 和 state 融合为 unifiedState
 *
 * 【第一层：系统最高优先级拦截 (优先判断 systemState)】
 * - shutdown -> FAULT: 底层硬件物理故障/热失控急停，必须人工介入物理干预
 * - error    -> SYS_ERROR: Klipper系统软件级配置或通讯错误
 * - startup  -> STARTING: 主板正在启动中
 * - ready    -> 放行，进入第二层判断
 *
 * 【第二层：业务状态判断 (当 systemState 为 ready 时，判断原 state)】
 * - standby   -> STANDBY: 打印机已准备就绪，即将开始或正在等待打印任务
 * - printing  -> PRINTING: 当前正在打印作业
 * - paused    -> PAUSED: 当前打印作业已暂停
 * - complete  -> COMPLETED: 最后一项打印任务已成功完成
 * - error     -> PRINT_ERROR: 最后一次打印作业出错并退出，如Gcode解析失败
 * - cancelled -> CANCELLED: 用户主动取消了最后一个打印作业
 */
@Data
@Schema(description = "Moonraker 打印机实时状态")
public class MoonrakerStatusDTO {

    // ==================== 统一状态常量定义 ====================
    // 第一层：系统级状态
    @Schema(description = "故障状态：底层硬件物理故障/热失控急停")
    public static final String UNIFIED_FAULT = "FAULT";
    @Schema(description = "系统错误状态：Klipper系统软件级配置或通讯错误")
    public static final String UNIFIED_SYS_ERROR = "SYS_ERROR";
    @Schema(description = "启动中状态：主板正在启动中")
    public static final String UNIFIED_STARTING = "STARTING";

    // 第二层：业务级状态
    @Schema(description = "待机状态：打印机已准备就绪")
    public static final String UNIFIED_STANDBY = "STANDBY";
    @Schema(description = "打印中状态：当前正在打印作业")
    public static final String UNIFIED_PRINTING = "PRINTING";
    @Schema(description = "暂停状态：当前打印作业已暂停")
    public static final String UNIFIED_PAUSED = "PAUSED";
    @Schema(description = "完成状态：最后一项打印任务已成功完成")
    public static final String UNIFIED_COMPLETED = "COMPLETED";
    @Schema(description = "打印错误状态：最后一次打印作业出错并退出")
    public static final String UNIFIED_PRINT_ERROR = "PRINT_ERROR";
    @Schema(description = "取消状态：用户主动取消了最后一个打印作业")
    public static final String UNIFIED_CANCELLED = "CANCELLED";
    @Schema(description = "未知状态")
    public static final String UNIFIED_UNKNOWN = "UNKNOWN";

    // ========== webhooks 相关字段 ==========
    /**
     * 系统状态: ready, startup, shutdown, error 等，用于判断底层主板是否离线或报错
     */
    @Schema(description = "系统状态: ready(就绪), startup(启动中), shutdown(关机), error(错误)", example = "ready")
    private String systemState;

    /**
     * 系统状态消息/错误信息
     */
    @Schema(description = "系统状态消息或错误信息")
    private String systemMessage;

    // ========== print_stats 相关字段 ==========
    /**
     * 机器状态: standby(待机), printing(打印中), paused(暂停), error(故障), complete(完成)
     */
    @Schema(description = "机器状态: standby(待机), printing(打印中), paused(暂停), error(故障), complete(完成)", example = "printing")
    private String state;

    /**
     * 文件名
     */
    @Schema(description = "当前打印文件名", example = "model.gcode")
    private String filename;

    /**
     * 打印持续时间 (秒)
     */
    @Schema(description = "打印持续时间（秒）", example = "3600")
    private Double printDuration;

    /**
     * 总持续时间 (秒)
     */
    @Schema(description = "总持续时间（秒）", example = "3600")
    private Double totalDuration;

    /**
     * 已用耗材 (毫米)
     */
    @Schema(description = "已用耗材长度（毫米）", example = "1250.5")
    private Double filamentUsed;

    // ========== display_status 相关字段 ==========
    /**
     * 打印进度 (0.00 - 100.00)
     */
    @Schema(description = "打印进度（0.00 - 100.00）", example = "45.5")
    private Double progress;

    // ========== extruder 相关字段 ==========
    /**
     * 喷头当前温度
     */
    @Schema(description = "喷头当前温度（℃）", example = "210.5")
    private Double toolTemperature;

    /**
     * 喷头目标温度
     */
    @Schema(description = "喷头目标温度（℃）", example = "210.0")
    private Double toolTarget;

    // ========== heater_bed 相关字段 ==========
    /**
     * 热床当前温度
     */
    @Schema(description = "热床当前温度（℃）", example = "60.0")
    private Double bedTemperature;

    /**
     * 热床目标温度
     */
    @Schema(description = "热床目标温度（℃）", example = "60.0")
    private Double bedTarget;

    // ========== 统一状态字段 ==========
    /**
     * 融合后的统一状态，由 calculateUnifiedStatus() 计算得出
     */
    @Schema(description = "融合后的统一状态: FAULT, SYS_ERROR, STARTING, STANDBY, PRINTING, PAUSED, COMPLETED, PRINT_ERROR, CANCELLED, UNKNOWN", example = "PRINTING")
    private String unifiedState;

    /**
     * 计算统一状态
     * <p>
     * 根据 systemState 和 state 按照严格的优先级融合为 unifiedState
     * 应在推送给前端或存入数据库前调用此方法
     *
     * @return 计算后的统一状态
     */
    public String calculateUnifiedStatus() {
        // 【第一层：系统最高优先级拦截】
        if (systemState != null) {
            switch (systemState.toLowerCase()) {
                case "shutdown":
                    this.unifiedState = UNIFIED_FAULT;
                    return this.unifiedState;
                case "error":
                    this.unifiedState = UNIFIED_SYS_ERROR;
                    return this.unifiedState;
                case "startup":
                    this.unifiedState = UNIFIED_STARTING;
                    return this.unifiedState;
                case "ready":
                    // 放行，进入第二层判断
                    break;
                default:
                    // 未知的 systemState，继续尝试第二层判断
                    break;
            }
        }

        // 【第二层：业务状态判断 (当 systemState 为 ready 或 null 时)】
        if (state != null) {
            switch (state.toLowerCase()) {
                case "standby":
                    this.unifiedState = UNIFIED_STANDBY;
                    break;
                case "printing":
                    this.unifiedState = UNIFIED_PRINTING;
                    break;
                case "paused":
                    this.unifiedState = UNIFIED_PAUSED;
                    break;
                case "complete":
                    this.unifiedState = UNIFIED_COMPLETED;
                    break;
                case "error":
                    this.unifiedState = UNIFIED_PRINT_ERROR;
                    break;
                case "cancelled":
                    this.unifiedState = UNIFIED_CANCELLED;
                    break;
                default:
                    this.unifiedState = UNIFIED_UNKNOWN;
                    break;
            }
        } else {
            this.unifiedState = UNIFIED_UNKNOWN;
        }

        return this.unifiedState;
    }

    /**
     * 获取统一状态（如果未计算则自动计算）
     *
     * @return 统一状态
     */
    public String getUnifiedState() {
        if (this.unifiedState == null) {
            return calculateUnifiedStatus();
        }
        return this.unifiedState;
    }
}
