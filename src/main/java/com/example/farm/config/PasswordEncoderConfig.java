package com.example.farm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密配置类
 * 提供 BCryptPasswordEncoder Bean 用于密码加密和校验
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * 创建 BCryptPasswordEncoder Bean
     * BCrypt 是一种自适应的哈希函数，内置盐值，安全性高
     *
     * @return PasswordEncoder 实例
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
