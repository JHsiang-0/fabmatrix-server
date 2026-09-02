package com.example.farm.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件上传约束，统一由 farm.file 配置绑定。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "farm.file")
public class FileUploadProperties {

    /** 允许的文件扩展名，不包含点号。 */
    private List<String> allowedTypes = new ArrayList<>(List.of("gcode", "g", "3mf", "stl"));

    /** 应用层文件大小上限，必须与 spring.servlet.multipart.max-file-size 一致。 */
    private DataSize maxFileSize = DataSize.ofMegabytes(200);
}
