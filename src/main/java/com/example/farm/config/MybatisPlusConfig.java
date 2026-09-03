package com.example.farm.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Properties;

/**
 * MyBatis-Plus 统一配置类 (包含分页插件与时间自动填充)
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 让自定义 Mapper SQL 可以按 MySQL/SQLite 选择方言；没有专用分支的 H2 测试继续使用默认语句。
     */
    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("MySQL", "mysql");
        properties.setProperty("SQLite", "sqlite");
        provider.setProperties(properties);
        return provider;
    }

    /**
     * 1. 注册分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(Environment environment) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        DbType dbType = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local"))
                ? DbType.SQLITE : DbType.MYSQL;
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(dbType));
        return interceptor;
    }

    /**
     * 2. 注册自动填充处理器 (内部类 Component)
     */
    @Component
    public static class MyMetaObjectHandler implements MetaObjectHandler {

        @Override
        public void insertFill(MetaObject metaObject) {
            // 插入时，自动填充 createdAt 和 updatedAt
            this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
            this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        }

        @Override
        public void updateFill(MetaObject metaObject) {
            // 更新时，自动填充 updatedAt
            this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
