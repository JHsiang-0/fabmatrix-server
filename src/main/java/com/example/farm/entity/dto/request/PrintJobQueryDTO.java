package com.example.farm.entity.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.example.farm.entity.enums.PrintJobTimeField;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 打印任务查询参数 DTO
 */
@Data
@Schema(description = "打印任务通用分页查询参数；status 与非空 statuses 互斥；时间按农场本地时区解释，默认 Asia/Shanghai")
public class PrintJobQueryDTO {

    @Schema(description = "页码，默认1", example = "1")
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNum = 1;

    @Schema(description = "每页数量，默认10", example = "10")
    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 10;

    @Schema(description = "兼容单值任务状态；支持规范状态及遗留别名 PENDING/MANUAL/CANCELED，别名分别映射为 QUEUED/QUEUED/CANCELLED；未知状态返回400")
    private String status;

    @ArraySchema(minItems = 1, maxItems = 10,
            schema = @Schema(allowableValues = {"QUEUED", "ASSIGNED", "UPLOADING", "READY", "PRINTING",
                    "PAUSED", "RECONCILING", "COMPLETED", "FAILED", "CANCELLED", "PENDING", "MANUAL", "CANCELED"}),
            arraySchema = @Schema(description = "多状态筛选，最多10项；非空时与 status 互斥；遗留别名只读映射为规范状态"))
    @Size(min = 1, max = 10, message = "任务状态不能为空且最多选择10项")
    private List<String> statuses;

    @Schema(maxLength = 255, description = "文件名字面包含筛选；服务端先 trim 再校验长度，忽略大小写并将 %, _、反斜杠按字面匹配；空白值视为 null，最多255字符")
    private String fileName;

    @Schema(description = "打印机ID")
    private Long printerId;

    @Schema(description = "用户ID（发起任务的用户）")
    private Long userId;

    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "时间范围起点；ISO-8601 local datetime，按农场时区解释，默认 Asia/Shanghai；包含边界")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime startTime;

    @Schema(type = "string", format = "date-time", pattern = "yyyy-MM-dd'T'HH:mm:ss", description = "时间范围终点；ISO-8601 local datetime，按农场时区解释，默认 Asia/Shanghai；包含边界")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime endTime;

    @Schema(description = "时间范围字段；默认 CREATED_AT。COMPLETED_AT 只切换筛选和排序字段，不自动限定终态")
    private PrintJobTimeField timeField = PrintJobTimeField.CREATED_AT;
}
