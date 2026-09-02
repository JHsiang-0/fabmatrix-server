package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 添加打印机请求 DTO
 */
@Data
@Schema(description = "添加打印机请求参数")
public class PrinterAddDTO {

    @Schema(description = "打印机名称 (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "局域网 IP 地址 (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ipAddress;

    @Schema(description = "MAC 地址 (选填，用于网络唤醒 WOL)")
    private String macAddress;

    @Schema(description = "固件类型 (默认 Klipper)")
    private String firmwareType = "Klipper";

    @Schema(description = "上位机 API 通信密钥")
    private String apiKey;

    @Schema(description = "当前装载耗材 (如 PLA, PETG, ABS)")
    private String currentMaterial;

    @Schema(description = "当前安装的喷嘴直径 (如 0.40, 0.60)")
    private BigDecimal nozzleSize;

    @Schema(description = "设备编号/机台号（用于产线管理）")
    private String machineNumber;

    @Schema(description = "物理位置 - 网格行号（数字孪生看板用，1-4，null 表示待分配区）")
    private Integer gridRow;

    @Schema(description = "物理位置 - 网格列号（数字孪生看板用，1-12，null 表示待分配区）")
    private Integer gridCol;
}
