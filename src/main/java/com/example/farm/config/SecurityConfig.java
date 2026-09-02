package com.example.farm.config;

import com.example.farm.common.api.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @org.springframework.beans.factory.annotation.Value("${farm.security.cors-allowed-origins:*}")
    private String corsAllowedOrigins;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private RequestTraceLogFilter requestTraceLogFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                renderSecurityError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        401, "未登录或登录已过期"))
                        .accessDeniedHandler((request, response, exception) ->
                                renderSecurityError(response, HttpServletResponse.SC_FORBIDDEN,
                                        403, "没有相关权限")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        // 本地农场不开放匿名注册，操作员账号由管理员创建
                        .requestMatchers("/api/v1/auth/register").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/check-username").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/check-email").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/admin/**").hasRole("ADMIN")

                        // 打印机：操作员可以查看状态，只有管理员可以增删改和扫描设备
                        .requestMatchers(HttpMethod.GET, "/api/v1/printers/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/v1/printers/**").hasRole("ADMIN")

                        // 文件：所有登录用户可以查看和下载，操作员及管理员可以上传、建目录、删除
                        .requestMatchers(HttpMethod.GET, "/api/v1/print-files/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/print-files/page")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/v1/print-files/**").hasAnyRole("ADMIN", "OPERATOR")

                        // 任务：所有登录用户可以查看队列，操作员及管理员负责提交和控制任务
                        .requestMatchers(HttpMethod.GET, "/api/v1/print-jobs/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/print-jobs/page")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/v1/print-jobs/**").hasAnyRole("ADMIN", "OPERATOR")

                        // 物理控制属于生产操作，管理员和操作员均可执行
                        .requestMatchers("/api/v1/control/**").hasAnyRole("ADMIN", "OPERATOR")

                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Moonraker API 模拟器（OrcaSlicer 等切片软件）
                        .requestMatchers(HttpMethod.OPTIONS, "/server/**", "/printer/**", "/machine/**", "/api/files/**").permitAll()
                        .requestMatchers("/server/**", "/printer/**", "/machine/**","/api/files/**").permitAll()
                        .anyRequest().authenticated()
                );

        // 请求追踪与访问日志过滤器（覆盖整条请求链）
        http.addFilterBefore(requestTraceLogFilter, SecurityContextHolderFilter.class);
        // JWT 认证过滤器（在 UsernamePasswordAuthenticationFilter 之前）
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void renderSecurityError(HttpServletResponse response, int statusCode,
                                     long code, String message) throws java.io.IOException {
        response.setStatus(statusCode);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(objectMapper.writeValueAsString(Result.failed(code, message)));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = parseCorsOrigins();
        if (origins.contains("*")) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(origins);
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> parseCorsOrigins() {
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toList());
    }
}
