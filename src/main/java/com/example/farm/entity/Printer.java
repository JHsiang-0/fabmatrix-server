package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 设备资产与状态表
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Getter
@Setter
@ToString
@TableName("farm_printer")
@Schema(name = "FarmPrinter", description = "设备资产与状态表")
public class Printer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 打印机名称（如：Voron-2.4-01）
     */
    @TableField("name")
    @Schema(description = "打印机名称（如：Voron-2.4-01）")
    private String name;

    /**
     * 局域网 IP 地址
     */
    @TableField("ip_address")
    @Schema(description = "局域网 IP 地址")
    private String ipAddress;

    /**
     * MAC 地址（用于网络唤醒等）
     */
    @TableField("mac_address")
    @Schema(description = "MAC 地址（用于网络唤醒等）")
    private String macAddress;

    /**
     * 固件类型（Klipper, OctoPrint 等）
     */
    @TableField("firmware_type")
    @Schema(description = "固件类型（Klipper, OctoPrint 等）")
    private String firmwareType;

    /**
     * 上位机 API 通信密钥
     */
    @TableField("api_key")
    @Schema(description = "上位机 API 通信密钥")
    private String apiKey;

    /**
     * 业务状态：IDLE, PRINTING, OFFLINE, ERROR, MAINTENANCE
     */
    @TableField("status")
    @Schema(description = "业务状态：IDLE, PRINTING, OFFLINE, ERROR, MAINTENANCE")
    private String status;

    /**
     * 热床是否已确认安全（0-未确认，1-已确认）
     * 防范风险：每次派单后重置为 false，必须现场操作员确认后才能启动打印
     */
    @TableField("is_safe_to_print")
    @Schema(description = "热床是否已确认安全（0-未确认，1-已确认）")
    private Boolean isSafeToPrint;

    /**
     * 当前正在执行的打印任务 ID
     */
    @TableField("current_job_id")
    @Schema(description = "当前正在执行的打印任务 ID")
    private Long currentJobId;

    /**
     * 录入时间
     */
    @TableField("created_at")
    @Schema(description = "录入时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    /**
     * 当前装载的耗材类型 (如 PLA, PETG, ABS)
     */
    @TableField("current_material")
    @Schema(description = "当前装载耗材 (如 PLA, PETG, ABS)")
    private String currentMaterial;

    /**
     * 当前安装的喷嘴直径 (如 0.40, 0.60)
     */
    @TableField("nozzle_size")
    @Schema(description = "当前安装的喷嘴直径 (如 0.40, 0.60)")
    private java.math.BigDecimal nozzleSize;

    /**
     * 设备编号/机台号（用于产线管理）
     */
    @TableField("machine_number")
    @Schema(description = "设备编号/机台号（用于产线管理）")
    private String machineNumber;

    /**
     * 物理位置 - 网格行号（数字孪生看板用，1-4，null 表示待分配区）
     */
    @TableField("grid_row")
    @Schema(description = "物理位置 - 网格行号（数字孪生看板用，1-4，null 表示待分配区）")
    private Integer gridRow;

    /**
     * 物理位置 - 网格列号（数字孪生看板用，1-12，null 表示待分配区）
     */
    @TableField("grid_col")
    @Schema(description = "物理位置 - 网格列号（数字孪生看板用，1-12，null 表示待分配区）")
    private Integer gridCol;
}
