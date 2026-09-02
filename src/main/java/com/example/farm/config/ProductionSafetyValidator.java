package com.example.farm.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * 生产环境启动前的安全配置检查。
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionSafetyValidator {

    private static final String DEV_JWT_SECRET = "Farm3DPrinterSuperSecretKey";
    private static final String DEV_ADMIN_SECRET = "FarmAdmin2024";

    @Value("${spring.datasource.url:}")
    private String mysqlUrl;
    @Value("${spring.datasource.username:}")
    private String mysqlUsername;
    @Value("${spring.datasource.password:}")
    private String mysqlPassword;
    @Value("${spring.data.redis.host:}")
    private String redisHost;
    @Value("${spring.data.redis.password:}")
    private String redisPassword;
    @Value("${rustfs.endpoint:}")
    private String rustfsEndpoint;
    @Value("${rustfs.access-key:}")
    private String rustfsAccessKey;
    @Value("${rustfs.secret-key:}")
    private String rustfsSecretKey;
    @Value("${jwt.secret-key:}")
    private String jwtSecretKey;
    @Value("${admin.secret-key:}")
    private String adminSecretKey;
    @Value("${farm.security.cors-allowed-origins:*}")
    private String corsAllowedOrigins;
    @Value("${springdoc.api-docs.enabled:true}")
    private boolean apiDocsEnabled;
    @Value("${springdoc.swagger-ui.enabled:true}")
    private boolean swaggerEnabled;

    @PostConstruct
    void validate() {
        require("spring.datasource.url", mysqlUrl);
        require("spring.datasource.username", mysqlUsername);
        require("MYSQL_PASSWORD", mysqlPassword);
        require("spring.data.redis.host", redisHost);
        require("REDIS_PASSWORD", redisPassword);
        require("rustfs.endpoint", rustfsEndpoint);
        require("RUSTFS_ACCESS_KEY", rustfsAccessKey);
        require("RUSTFS_SECRET_KEY", rustfsSecretKey);
        require("JWT_SECRET_KEY", jwtSecretKey);
        require("ADMIN_SECRET_KEY", adminSecretKey);

        if (DEV_JWT_SECRET.equals(jwtSecretKey) || DEV_ADMIN_SECRET.equals(adminSecretKey)) {
            throw new IllegalStateException("生产环境不能使用开发默认密钥");
        }
        if (!StringUtils.hasText(corsAllowedOrigins)
                || Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .anyMatch("*"::equals)) {
            throw new IllegalStateException("生产环境必须配置明确的 CORS 来源，不能使用 *");
        }
        if (apiDocsEnabled || swaggerEnabled) {
            throw new IllegalStateException("生产环境默认必须关闭 Swagger 和 OpenAPI 文档");
        }
        log.info("生产环境安全配置检查通过");
    }

    private void require(String name, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("缺少生产环境必需配置: " + name);
        }
    }
}
