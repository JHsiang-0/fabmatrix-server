package com.example.farm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类
 * 使用 String 序列化，手动处理 JSON 转换
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate
     * 使用 StringRedisSerializer 避免弃用 API
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 统一使用 StringRedisSerializer
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 独立构建的 ObjectMapper
     * 不依赖 Jackson2ObjectMapperBuilder，彻底规避 Spring 启动顺序导致的找不到 Bean 异常
     */
    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 🚀 核心大招：让 Jackson 自己去扫描并加载所有可用的模块（包括 Java 8 时间模块）
        mapper.findAndRegisterModules();

        // 禁用将日期写为时间戳，使用 ISO-8601 格式字符串 (例如 "2026-03-03T15:30:45")
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        return mapper;
    }

}