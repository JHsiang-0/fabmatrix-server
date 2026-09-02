package com.example.farm;

import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.util.Collections;

public class MyBatisPlusGenerator {

    public static void main(String[] args) {
        // 1. 数据库连接配置 (请修改为你自己的 db_name, username, password)
        String url = "jdbc:mysql://localhost:3306/farm?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
        String username = "farm";
        String password = "YBW5esRQwm4fm3X2";

        // 获取项目根目录
        String projectPath = System.getProperty("user.dir");

        // 2. 核心执行器 FastAutoGenerator (3.5.x 核心)
        FastAutoGenerator.create(url, username, password)
                // 全局配置
                .globalConfig(builder -> {
                    builder.author("codexiang")             // 设置作者
                            .outputDir(projectPath + "/src/main/java") // 指定 Java 代码输出目录
                            .disableOpenDir()              // 生成后不自动打开系统文件夹
                            .enableSpringdoc();            // 开启 Swagger/SpringDoc 注解 (可选)
                })
                // 包配置
                .packageConfig(builder -> {
                    builder.parent("com.example.farm")     // 设置父包名
                            // 单独配置 Mapper XML 的输出路径到 resources 下
                            .pathInfo(Collections.singletonMap(OutputFile.xml, projectPath + "/src/main/resources/mapper"));
                })
                // 策略配置 (核心重点)
                .strategyConfig(builder -> {
                    builder.addInclude("farm_user", "farm_file", "farm_printer", "print_job") // 要生成的表名
                            // .addTablePrefix("farm_")    // (可选) 如果你想让生成的类名去掉 farm_ 前缀 (例如 FarmUser 变成 User)，解开此注释

                            // Entity (实体类) 策略配置
                            .entityBuilder()
                            .enableLombok()                // 开启 Lombok
                            .enableTableFieldAnnotation()  // 开启 @TableField 注解
                            .enableFileOverride()          // 允许覆盖已有文件 (3.5.15 新版写法)

                            // Mapper 策略配置
                            .mapperBuilder()
                            .enableMapperAnnotation()      // 开启 @Mapper 注解
                            .enableFileOverride()          // 允许覆盖

                            // Service 策略配置
                            .serviceBuilder()
                            .formatServiceFileName("%sService") // 格式化接口名称，去掉默认的 "I" 前缀
                            .enableFileOverride()          // 允许覆盖

                            // Controller 策略配置
                            .controllerBuilder()
                            .enableRestStyle()             // 开启 @RestController 风格
                            .enableFileOverride();         // 允许覆盖
                })
                // 设置模板引擎 (默认是 Velocity，我们这里指定为 Freemarker)
                .templateEngine(new FreemarkerTemplateEngine())
                // 执行生成
                .execute();

        System.out.println("🎉 基于 MyBatis-Plus 3.5.15 代码生成完毕！请刷新 IDE 查看代码。");
    }
}