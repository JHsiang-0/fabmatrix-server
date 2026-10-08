package com.example.farm.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 同步报表导出的有界查询限制。 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "farm.export")
public class ExportProperties {
    @Min(1)
    @Max(10000)
    private int xlsxMaxRows = 10000;

    @Min(1)
    @Max(1000)
    private int pdfMaxRows = 1000;
}
