package com.example.farm;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/** 手动生成工具：凭据由环境注入，生成结果必须人工审查后移入源码目录。 */
public class MyBatisPlusGenerator {

    public static void main(String[] args) {
        String url = requireEnvironment("GENERATOR_DATABASE_URL");
        String username = requireEnvironment("GENERATOR_DATABASE_USERNAME");
        String password = requireEnvironment("GENERATOR_DATABASE_PASSWORD");
        String[] tables = requireEnvironment("GENERATOR_TABLES").split(",", -1);
        for (int i = 0; i < tables.length; i++) {
            tables[i] = tables[i].trim();
            if (!tables[i].matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new IllegalArgumentException("GENERATOR_TABLES 必须为逗号分隔的完整表名");
            }
        }
        Path output = Path.of(System.getProperty("user.dir"), "target", "codegen").toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalStateException("target/codegen 已存在，请先另存或人工清理旧结果后再运行");
        }
        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> builder.author("FabMatrix")
                        .outputDir(output.resolve("java").toString())
                        .disableOpenDir()
                        .enableSpringdoc())
                .packageConfig(builder -> builder.parent("com.example.farm")
                        .pathInfo(Collections.singletonMap(OutputFile.xml,
                                output.resolve("resources/mapper").toString())))
                .strategyConfig(builder -> builder.addInclude(tables)
                        .entityBuilder().enableLombok().enableTableFieldAnnotation()
                        .mapperBuilder().enableMapperAnnotation()
                        .serviceBuilder().formatServiceFileName("%sService")
                        .controllerBuilder().enableRestStyle())
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();
        System.out.println("代码已生成至 " + output + "，请审查后手动合入。");
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量：" + name);
        }
        return value;
    }
}
