package com.example.farm.entity.vo;

import com.example.farm.entity.dto.MoonrakerStatusDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 打印机详情安全响应对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "PrinterDetailVO", description = "打印机详情，包含安全配置、实时缓存和当前任务摘要")
public class PrinterDetailVO {

    @Schema(description = "打印机安全配置")
    private PrinterVO printer;

    @Schema(description = "实时状态缓存，未命中时为 null")
    private MoonrakerStatusDTO realtimeStatus;

    @Schema(description = "当前绑定任务摘要，没有任务时为 null")
    private PrintJobVO currentJob;
}
