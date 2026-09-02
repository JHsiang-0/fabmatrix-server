package com.example.farm.entity.vo;

import com.example.farm.entity.Printer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 打印机精简信息 VO（用于下拉列表等场景）
 * </p>
 *
 * @author codexiang
 * @since 2026-03-05
 */
@Data
@Schema(name = "PrinterVO", description = "打印机安全响应对象，不包含 apiKey")
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
     * 业务状态：OFFLINE, IDLE, PREPARING, PRINTING, PAUSED, ERROR, UNKNOWN
     */
    @Schema(description = "业务状态：OFFLINE(离线), IDLE(空闲), PREPARING(准备中), PRINTING(打印中), PAUSED(暂停), ERROR(故障), UNKNOWN(未知)")
    private String status;

    @Schema(description = "固件类型")
    private String firmwareType;

    @Schema(description = "热床是否已确认安全")
    private Boolean isSafeToPrint;

    @Schema(description = "当前任务 ID")
    private Long currentJobId;

    @Schema(description = "当前装载耗材")
    private String currentMaterial;

    @Schema(description = "喷嘴直径")
    private BigDecimal nozzleSize;

    @Schema(description = "网格行号")
    private Integer gridRow;

    @Schema(description = "网格列号")
    private Integer gridCol;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static PrinterVO from(Printer printer) {
        if (printer == null) {
            return null;
        }
        PrinterVO vo = new PrinterVO();
        vo.id = printer.getId();
        vo.name = printer.getName();
        vo.machineNumber = printer.getMachineNumber();
        vo.ipAddress = printer.getIpAddress();
        vo.macAddress = printer.getMacAddress();
        vo.status = printer.getStatus();
        vo.firmwareType = printer.getFirmwareType();
        vo.isSafeToPrint = printer.getIsSafeToPrint();
        vo.currentJobId = printer.getCurrentJobId();
        vo.currentMaterial = printer.getCurrentMaterial();
        vo.nozzleSize = printer.getNozzleSize();
        vo.gridRow = printer.getGridRow();
        vo.gridCol = printer.getGridCol();
        vo.createdAt = printer.getCreatedAt();
        vo.updatedAt = printer.getUpdatedAt();
        return vo;
    }
}
