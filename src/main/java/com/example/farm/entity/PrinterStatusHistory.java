package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印机状态历史样本。
 *
 * <p>Redis 仍负责最近状态的高频缓存，本表保存状态变化或定期采样结果，
 * 用于分页查询和服务重启后的历史查看。</p>
 */
@Getter
@Setter
@TableName("farm_printer_status_history")
@Schema(name = "PrinterStatusHistory", description = "打印机状态历史样本")
public class PrinterStatusHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("printer_id")
    private Long printerId;

    @TableField("status")
    private String status;

    @TableField("raw_state")
    private String rawState;

    @TableField("system_message")
    private String systemMessage;

    @TableField("filename")
    private String filename;

    @TableField("progress")
    private BigDecimal progress;

    @TableField("tool_temperature")
    private BigDecimal toolTemperature;

    @TableField("tool_target")
    private BigDecimal toolTarget;

    @TableField("bed_temperature")
    private BigDecimal bedTemperature;

    @TableField("bed_target")
    private BigDecimal bedTarget;

    @TableField("print_duration")
    private BigDecimal printDuration;

    @TableField("total_duration")
    private BigDecimal totalDuration;

    @TableField("filament_used")
    private BigDecimal filamentUsed;

    @TableField("recorded_at")
    private LocalDateTime recordedAt;
}
