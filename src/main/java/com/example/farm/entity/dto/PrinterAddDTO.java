package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 添加打印机请求 DTO
 */
@Data
@Schema(description = "添加打印机请求参数")
public class PrinterAddDTO {

    @Schema(description = "打印机名称 (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "打印机名称不能为空")
    @Size(max = 100, message = "打印机名称不能超过100个字符")
    @Pattern(regexp = ".*\\S.*", message = "打印机名称不能只包含空白字符")
    private String name;

    @Schema(description = "局域网 IP 地址 (必填)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "打印机 IP 地址不能为空")
    @Pattern(regexp = "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})$",
            message = "打印机 IP 地址格式不正确")
    private String ipAddress;

    @Schema(description = "MAC 地址 (选填，用于网络唤醒 WOL)")
    @Pattern(regexp = "(?i)^(?:$|(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})$", message = "MAC 地址格式不正确")
    private String macAddress;

    @Schema(description = "固件类型 (默认 KLIPPER)")
    @Pattern(regexp = "(?i)^(KLIPPER|RRF)$", message = "固件类型只能是 KLIPPER 或 RRF")
    private String firmwareType = "KLIPPER";

    @Schema(description = "上位机 API 通信密钥")
    private String apiKey;

    @Schema(description = "当前装载耗材 (如 PLA, PETG, ABS)")
    private String currentMaterial;

    @Schema(description = "当前安装的喷嘴直径 (如 0.40, 0.60)")
    @DecimalMin(value = "0.1", message = "喷嘴直径必须大于等于0.1")
    @DecimalMax(value = "2.0", message = "喷嘴直径不能超过2.0")
    private BigDecimal nozzleSize;

    @Schema(description = "设备编号/机台号（用于产线管理）")
    private String machineNumber;

    @Schema(description = "物理位置 - 网格行号（数字孪生看板用，1-4，null 表示待分配区）")
    @Min(value = 1, message = "网格行号必须在1-4之间")
    @Max(value = 4, message = "网格行号必须在1-4之间")
    private Integer gridRow;

    @Schema(description = "物理位置 - 网格列号（数字孪生看板用，1-12，null 表示待分配区）")
    @Min(value = 1, message = "网格列号必须在1-12之间")
    @Max(value = 12, message = "网格列号必须在1-12之间")
    private Integer gridCol;
}
