package com.example.farm.entity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印任务查询摘要。
 *
 * <p>查询接口直接从任务、文件、打印机和用户表关联读取可读字段，
 * 不通过逐任务详情请求补齐摘要。</p>
 */
@Data
@Schema(name = "PrintJobSummaryVO", description = "打印任务可读查询摘要")
public class PrintJobSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", format = "int64", description = "任务 ID；JSON 十进制字符串")
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", types = {"null"}, format = "int64",
            description = "切片文件 ID；JSON 十进制字符串；无关联文件时为 null")
    private Long fileId;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", types = {"null"}, format = "int64",
            description = "绑定打印机 ID；JSON 十进制字符串；排队任务为 null")
    private Long printerId;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", format = "int64", description = "发起任务的用户 ID；JSON 十进制字符串")
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", types = {"null"}, format = "int64",
            description = "现场操作员用户 ID；JSON 十进制字符串；未操作时为 null")
    private Long operatorId;

    @Schema(types = {"integer", "null"}, description = "任务优先级，范围 0-100；旧数据可能为 null")
    private Integer priority;

    @Schema(description = "任务状态")
    private String status;

    @Schema(types = {"number", "null"}, description = "打印进度，范围 0-100；旧数据可能为 null")
    private BigDecimal progress;

    @Schema(types = {"string", "null"}, format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss",
            description = "实际开始时间；未开始时为 null")
    private LocalDateTime startedAt;

    @Schema(types = {"string", "null"}, format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss",
            description = "实际完成或失败时间；未结束时为 null")
    private LocalDateTime completedAt;

    @Schema(types = {"string", "null"}, description = "失败或核对原因；无原因时为 null")
    private String errorReason;

    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;

    @Schema(types = {"string", "null"}, description = "当前文件名称；关联文件不存在时为 null")
    private String fileName;

    @Schema(types = {"string", "null"}, description = "当前打印机名称；未分配或关联打印机不存在时为 null")
    private String printerName;

    @Schema(types = {"string", "null"}, description = "当前机器编号；关联打印机不存在时为 null")
    private String machineNumber;

    @Schema(types = {"boolean", "null"},
            description = "当前任务绑定的打印机是否已完成现场安全确认；未绑定或绑定已变化时为 false")
    private Boolean safeToPrint;

    @Schema(types = {"string", "null"}, description = "创建人用户名；关联用户不存在时为 null")
    private String creatorUsername;

    @Schema(types = {"string", "null"}, description = "操作员用户名；关联用户不存在时为 null")
    private String operatorUsername;

    @Schema(types = {"string", "null"}, description = "文件摘要中的耗材类型；无值或关联文件不存在时为 null")
    private String materialType;

    @Schema(types = {"number", "null"}, description = "文件摘要中的喷嘴尺寸；JSON number；无值或关联文件不存在时为 null")
    private BigDecimal nozzleSize;

    @Schema(types = {"string", "null"}, description = "历史文件名快照；资源删除或改名后仍可读")
    private String fileNameSnapshot;

    @Schema(types = {"string", "null"}, description = "历史打印机名称快照；资源删除或改名后仍可读")
    private String printerNameSnapshot;

    @Schema(types = {"string", "null"}, description = "历史机器编号快照；资源删除或改名后仍可读")
    private String machineNumberSnapshot;

    @Schema(types = {"string", "null"}, description = "历史创建人用户名快照；资源删除或改名后仍可读")
    private String creatorUsernameSnapshot;

    @Schema(types = {"string", "null"}, description = "真正启动打印时的操作员用户名快照")
    private String operatorUsernameSnapshot;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", types = {"null"}, format = "int64",
            description = "直接来源任务 ID；JSON 十进制字符串；非重试任务为 null")
    private Long retryOfJobId;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(type = "string", types = {"null"}, format = "int64",
            description = "首次任务 ID；JSON 十进制字符串；根任务指向自身")
    private Long rootJobId;

    @Schema(types = {"integer", "null"}, description = "任务尝试序号；新任务默认为1")
    private Integer attemptNumber;
}
