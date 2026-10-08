package com.example.farm.entity.dto.request;

import com.example.farm.common.export.ExportFormat;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 历史导出采用结束时间，不接受分页或客户端指定时间字段。 */
@Data
@Schema(description = "打印历史导出参数；结束时间筛选，Asia/Shanghai，导出全部匹配的已结束平台任务")
public class PrintJobHistoryExportRequest {
    @NotNull(message = "导出格式不能为空")
    @Schema(description = "导出格式", allowableValues = {"XLSX", "PDF"})
    private ExportFormat format;

    @Size(min = 1, max = 3, message = "历史状态至少选择1项，最多3项")
    @ArraySchema(minItems = 1, maxItems = 3,
            schema = @Schema(allowableValues = {"COMPLETED", "FAILED", "CANCELLED", "CANCELED"}),
            arraySchema = @Schema(description = "可省略，默认全部已结束状态；CANCELED 只读映射为 CANCELLED"))
    private List<String> statuses;

    @Schema(maxLength = 255, description = "文件名字面包含，trim 后最多255字符；与历史列表筛选语义一致")
    private String fileName;

    @Positive(message = "打印机ID必须大于0")
    @Schema(type = "string", format = "int64", description = "打印机 ID，建议提交十进制字符串")
    private Long printerId;

    @Positive(message = "用户ID必须大于0")
    @Schema(type = "string", format = "int64", description = "任务创建人 ID；管理员可筛选，操作员始终仅导出自己的任务")
    private Long userId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss", lenient = OptBoolean.FALSE)
    @Schema(type = "string", format = "date-time", description = "结束时间范围起点，包含边界；农场时区 Asia/Shanghai")
    private LocalDateTime startTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "uuuu-MM-dd'T'HH:mm:ss", lenient = OptBoolean.FALSE)
    @Schema(type = "string", format = "date-time", description = "结束时间范围终点，包含边界；农场时区 Asia/Shanghai")
    private LocalDateTime endTime;
}
