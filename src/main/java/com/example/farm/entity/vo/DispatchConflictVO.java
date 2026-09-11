package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 批量预览冲突。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DispatchConflictVO", description = "批量派发预览冲突")
public class DispatchConflictVO {
    @Schema(description = "计划明细 ID")
    private String itemId;
    @Schema(description = "文件 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;
    @Schema(description = "打印机 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long printerId;
    @Schema(description = "稳定原因码")
    private String reasonCode;
    @Schema(description = "冲突说明")
    private String message;
}
