package com.example.farm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FarmApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
        assertThat(applicationContext.containsBean("printerMonitorTask")).isFalse();
        assertThat(applicationContext.containsBean("jobSchedulerTask")).isFalse();
        assertThat(environment.getProperty("rustfs.startup-check-enabled", Boolean.class)).isFalse();
    }

    @Test
    void configuresLargeFileAndRequestUploadLimits() {
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("1GB");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("1288490189B");
        assertThat(environment.getProperty("server.tomcat.max-part-count")).isEqualTo("101");
        assertThat(environment.getProperty("server.tomcat.connection-timeout", Duration.class))
                .isEqualTo(Duration.ofMinutes(30));
    }

}
