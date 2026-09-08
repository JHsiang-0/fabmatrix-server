package com.example.farm.entity.dto;

import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/** 更新单台打印机的后台监控开关。 */
@Data
public class PrinterMonitorUpdateDTO {

    @NotNull(message = "monitorEnabled 不能为空")
    @JsonAlias("enabled")
    private Boolean monitorEnabled;
}
