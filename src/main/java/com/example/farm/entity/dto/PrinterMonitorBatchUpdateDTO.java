package com.example.farm.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.util.List;

/** 批量更新打印机监控开关。 */
@Data
public class PrinterMonitorBatchUpdateDTO {

    @NotEmpty(message = "打印机 ID 列表不能为空")
    @Size(max = 100, message = "单次最多更新100台打印机")
    @Valid
    private List<@NotNull(message = "打印机 ID 不能为空") Long> printerIds;

    @NotNull(message = "monitorEnabled 不能为空")
    @JsonAlias("enabled")
    private Boolean monitorEnabled;
}
