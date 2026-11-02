package com.example.farm.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;

@Configuration
@Profile("local")
public class SqliteInstanceGuardConfiguration {
    @Bean
    static BeanFactoryPostProcessor guardBeforeDataSource() {
        return factory -> {
            if (factory.containsBeanDefinition("dataSource")) {
                var definition = factory.getBeanDefinition("dataSource");
                var dependencies = new LinkedHashSet<String>();
                if (definition.getDependsOn() != null) dependencies.addAll(List.of(definition.getDependsOn()));
                dependencies.add("sqliteInstanceGuard");
                definition.setDependsOn(dependencies.toArray(String[]::new));
            }
        };
    }

    @Bean(destroyMethod = "close")
    SqliteInstanceGuard sqliteInstanceGuard(@Value("${spring.datasource.url}") String url) throws IOException {
        return new SqliteInstanceGuard(url);
    }
}
