package com.example.farm.entity.vo;

import com.example.farm.entity.PrinterStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印机状态历史安全响应对象。
 */
@Data
@Schema(name = "PrinterStatusHistoryVO", description = "打印机状态历史安全响应")
public class PrinterStatusHistoryVO {

    private Long id;
    private Long printerId;
    @Schema(description = "统一状态：IDLE、PRINTING、PAUSED、ERROR、OFFLINE 等")
    private String status;
    @Schema(description = "设备原始状态")
    private String rawState;
    private String systemMessage;
    private String filename;
    private BigDecimal progress;
    private BigDecimal toolTemperature;
    private BigDecimal toolTarget;
    private BigDecimal bedTemperature;
    private BigDecimal bedTarget;
    private BigDecimal printDuration;
    private BigDecimal totalDuration;
    private BigDecimal filamentUsed;
    @Schema(description = "记录时间，ISO-8601 本地时间")
    private LocalDateTime recordedAt;

    public static PrinterStatusHistoryVO from(PrinterStatusHistory history) {
        if (history == null) {
            return null;
        }
        PrinterStatusHistoryVO vo = new PrinterStatusHistoryVO();
        vo.id = history.getId();
        vo.printerId = history.getPrinterId();
        vo.status = history.getStatus();
        vo.rawState = history.getRawState();
        vo.systemMessage = history.getSystemMessage();
        vo.filename = history.getFilename();
        vo.progress = history.getProgress();
        vo.toolTemperature = history.getToolTemperature();
        vo.toolTarget = history.getToolTarget();
        vo.bedTemperature = history.getBedTemperature();
        vo.bedTarget = history.getBedTarget();
        vo.printDuration = history.getPrintDuration();
        vo.totalDuration = history.getTotalDuration();
        vo.filamentUsed = history.getFilamentUsed();
        vo.recordedAt = history.getRecordedAt();
        return vo;
    }
}
