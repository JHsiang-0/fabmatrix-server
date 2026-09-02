package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 打印机扫描结果 DTO
 * <p>包含设备扫描时获取的完整信息</p>
 */
@Data
@Schema(description = "打印机扫描结果")
public class PrinterScanResultDTO {

    @Schema(description = "IP 地址", example = "192.168.1.100")
    private String ipAddress;

    @Schema(description = "MAC 地址（标准化格式）", example = "00:11:22:33:44:55")
    private String macAddress;

    @Schema(description = "固件类型", example = "Klipper")
    private String firmwareType;

    @Schema(description = "是否为新设备（数据库中不存在该 MAC）", example = "true")
    private Boolean isNewDevice;

    @Schema(description = "设备状态：ONLINE, OFFLINE, EXISTING(已存在)", example = "ONLINE")
    private String status;

    @Schema(description = "API 密钥（如果有）")
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
        dto.setFirmwareType("Klipper");
        return dto;
    }
}