package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * <p>
 * 打印机精简信息 VO（用于下拉列表等场景）
 * </p>
 *
 * @author codexiang
 * @since 2026-03-05
 */
@Data
@Schema(name = "PrinterVO", description = "打印机精简信息（用于下拉列表）")
public class PrinterVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 打印机名称
     */
    @Schema(description = "打印机名称")
    private String name;

    /**
     * 设备编号/机台号
     */
    @Schema(description = "设备编号/机台号")
    private String machineNumber;

    /**
     * 局域网 IP 地址
     */
    @Schema(description = "局域网 IP 地址")
    private String ipAddress;

    /**
     * MAC 地址
     */
    @Schema(description = "MAC 地址")
    private String macAddress;

    /**
     * 业务状态：IDLE, PRINTING, OFFLINE, ERROR, MAINTENANCE
     */
    @Schema(description = "业务状态：IDLE(空闲), PRINTING(打印中), OFFLINE(离线), ERROR(故障), MAINTENANCE(维护)")
    private String status;
}
