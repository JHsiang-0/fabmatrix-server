package com.example.farm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** Local Edition 启动时检查数据目录可写和基本磁盘空间。 */
@Slf4j
@Component
@Profile("local")
public class LocalEditionStartupCheck implements org.springframework.boot.ApplicationRunner {
    private static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

    @Value("${FARM_DATA_DIR:${user.home}/FarmData}")
    private String dataDirectory;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        Path dataPath = Path.of(dataDirectory).toAbsolutePath().normalize();
        Files.createDirectories(dataPath);
        if (!Files.isWritable(dataPath)) {
            throw new IllegalStateException("Local Edition 数据目录不可写: " + dataPath);
        }
        long usable = Files.getFileStore(dataPath).getUsableSpace();
        if (usable < MIN_FREE_BYTES) {
            throw new IllegalStateException("Local Edition 可用磁盘空间不足100MB: " + dataPath);
        }
        log.info("Local Edition 数据目录检查通过: path={}, usableSpaceBytes={}", dataPath, usable);
    }
}
