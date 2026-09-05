package com.example.farm.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** FabMatrix OpenAPI 基础信息和认证方案配置。 */
@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI fabMatrixOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("FabMatrix Server API")
                        .version("v2.0")
                        .description("FabMatrix 3D 打印生产管理服务 API。REST 业务接口统一返回 code、message、data、timestamp；生产环境必须关闭 OpenAPI 和 Swagger UI。")
                        .contact(new Contact().name("FabMatrix")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 JWT，格式：Bearer <token>"))
                        .addSecuritySchemes("moonrakerApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("仅 dev/test 的 Moonraker 兼容接口可能启用；是否必填由 moonraker-api-key 配置决定。")));
    }

    /**
     * 为业务 REST 操作补充统一异常响应，避免 Swagger 只显示成功响应。
     * 具体业务码和是否触发仍以 API_HANDOFF.md 及实际接口校验为准。
     */
    @Bean
    public OpenApiCustomizer commonErrorResponses() {
        return openAPI -> openAPI.getPaths().forEach((path, pathItem) -> {
            if (!path.startsWith("/api/v1/")) {
                return;
            }
            for (Operation operation : pathItem.readOperations()) {
                addResponse(operation, "400", "请求参数校验失败，统一返回 Result");
                addResponse(operation, "404", "目标资源不存在，统一返回 Result");
                addResponse(operation, "409", "资源或幂等请求冲突，统一返回 Result");
                addResponse(operation, "422", "当前业务状态不允许操作，统一返回 Result");
                addResponse(operation, "500", "服务端异常，统一返回 Result");
                addResponse(operation, "503", "依赖服务或设备暂不可用，统一返回 Result");
                if (!path.equals("/api/v1/auth/login")
                        && !path.equals("/api/v1/auth/setup/status")
                        && !path.equals("/api/v1/auth/setup/admin")) {
                    addResponse(operation, "401", "未登录或 Bearer Token 无效");
                    addResponse(operation, "403", "当前用户没有该操作权限");
                }
            }
        });
    }

    private void addResponse(Operation operation, String code, String description) {
        if (!operation.getResponses().containsKey(code)) {
            operation.getResponses().addApiResponse(code, new ApiResponse().description(description));
        }
    }
}
