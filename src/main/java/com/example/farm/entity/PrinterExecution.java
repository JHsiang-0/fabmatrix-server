package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 持久化一次设备打印执行，用于跨轮询、跨服务重启确认任务终态。 */
@Getter
@Setter
@TableName("farm_printer_execution")
public class PrinterExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("printer_id")
    private Long printerId;

    @TableField("farm_job_id")
    private Long farmJobId;

    @TableField("source")
    private String source;

    @TableField("state")
    private String state;

    @TableField("expected_filename")
    private String expectedFilename;

    @TableField("observed_filename")
    private String observedFilename;

    @TableField("has_seen_processing")
    private Boolean hasSeenProcessing;

    @TableField("has_seen_paused")
    private Boolean hasSeenPaused;

    @TableField("idle_observation_count")
    private Integer idleObservationCount;

    @TableField("last_raw_state")
    private String lastRawState;

    @TableField("max_progress")
    private BigDecimal maxProgress;

    @TableField("max_file_position")
    private BigDecimal maxFilePosition;

    @TableField("file_size")
    private BigDecimal fileSize;

    @TableField("device_uptime_at_start")
    private Long deviceUptimeAtStart;

    @TableField("device_uptime_last")
    private Long deviceUptimeLast;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("last_seen_at")
    private LocalDateTime lastSeenAt;

    @TableField("ended_at")
    private LocalDateTime endedAt;

    @TableField("end_reason")
    private String endReason;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
