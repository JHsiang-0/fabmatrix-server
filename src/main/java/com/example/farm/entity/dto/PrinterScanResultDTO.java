package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 打印机扫描结果 DTO
 * <p>包含设备扫描时获取的完整信息</p>
 */
@Data
@Schema(description = "打印机扫描结果")
public class PrinterScanResultDTO {

    @Schema(description = "IP 地址", example = "192.168.1.100")
    @NotBlank(message = "扫描结果中的 IP 地址不能为空")
    @Pattern(regexp = "^(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})$",
            message = "扫描结果中的 IP 地址格式不正确")
    private String ipAddress;

    @Schema(description = "MAC 地址（标准化格式）", example = "00:11:22:33:44:55")
    @Pattern(regexp = "(?i)^(?:$|(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2})$", message = "扫描结果中的 MAC 地址格式不正确")
    private String macAddress;

    @Schema(description = "固件类型", example = "KLIPPER")
    @Pattern(regexp = "(?i)^(KLIPPER|RRF)$", message = "固件类型只能是 KLIPPER 或 RRF")
    private String firmwareType;

    @Schema(description = "是否为新设备（数据库中不存在该 MAC）", example = "true")
    private Boolean isNewDevice;

    @Schema(description = "扫描发现状态：ONLINE(已发现), EXISTING(数据库已存在)", example = "ONLINE")
    private String status;

    @Schema(description = "API 密钥（如果有）")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    @Schema(description = "建议的默认名称", example = "Printer_4455")
    private String suggestedName;

    /**
     * 快速创建扫描结果对象的工厂方法
     */
    public static PrinterScanResultDTO of(String ip, String mac, boolean isNew) {
        PrinterScanResultDTO dto = new PrinterScanResultDTO();
        dto.setIpAddress(ip);
        dto.setMacAddress(mac);
        dto.setIsNewDevice(isNew);
        dto.setStatus(isNew ? "ONLINE" : "EXISTING");
        dto.setFirmwareType("KLIPPER");
        return dto;
    }
}
