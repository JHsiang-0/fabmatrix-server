package com.example.farm.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 所有部署形态共用的 JSON 配置；Local Edition 也需要 JWT 和 WebSocket 使用。 */
@Configuration
public class CommonObjectMapperConfig {
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        SimpleModule longAsString = new SimpleModule("long-as-string");
        JsonSerializer<Long> serializer = new JsonSerializer<>() {
            @Override
            public void serialize(Long value, JsonGenerator generator,
                                  com.fasterxml.jackson.databind.SerializerProvider provider)
                    throws java.io.IOException {
                generator.writeString(Long.toString(value));
            }
        };
        longAsString.addSerializer(Long.class, serializer);
        longAsString.addSerializer(Long.TYPE, serializer);
        mapper.registerModule(longAsString);
        return mapper;
    }
}
