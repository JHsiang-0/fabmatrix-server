package com.example.farm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * 全局跨域配置类
 * 解决前后端分离架构下，前端调用的 CORS 跨域拦截问题
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${farm.security.cors-allowed-origins:*}")
    private String corsAllowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 设置允许跨域的路径
        var registration = registry.addMapping("/**");
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.contains("*")) {
            registration.allowedOriginPatterns("*");
        } else {
            registration.allowedOrigins(origins.toArray(String[]::new));
        }
        registration
                // 是否允许 cookie
                .allowCredentials(true)
                // 设置允许的请求方式
                .allowedMethods("GET", "POST", "DELETE", "PUT", "OPTIONS")
                // 设置允许的 header 属性
                .allowedHeaders("*")
                // 跨域允许时间 (秒)
                .maxAge(3600);
    }
}
