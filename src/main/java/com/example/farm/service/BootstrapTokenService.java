package com.example.farm.service;

import com.example.farm.entity.dto.BootstrapTokenDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Base64;

/**
 * 读取 Local Edition 安装器生成的 bootstrap 凭据。
 * 凭据文件位于受安装器 ACL 保护的配置目录，成功初始化后由事务提交回调删除；
 * 数据库中的 setup 状态同时作为最终的永久失效边界。
 */
@Component
@Slf4j
public class BootstrapTokenService {

    private static final long TOKEN_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path tokenFile;
    private final Clock clock;

    @Autowired
    public BootstrapTokenService(
            @Value("${farm.security.bootstrap-token-file:${FARM_DATA_DIR:${user.home}/FarmData}/config/bootstrap.token}")
            String tokenFile) {
        this(Path.of(tokenFile), Clock.systemUTC());
    }

    BootstrapTokenService(Path tokenFile, Clock clock) {
        this.tokenFile = tokenFile.toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** 返回当前仍有效的凭据，不把文件内容缓存到进程内。 */
    public synchronized BootstrapTokenDTO getValidCredential() {
        Credential credential = readCredential();
        if (credential == null || credential.expiresAt() <= clock.millis()) {
            return null;
        }
        return new BootstrapTokenDTO(credential.token(), credential.expiresAt());
    }

    /**
     * 返回当前凭据，缺失或过期时在受保护的配置目录中轮换新凭据。
     * 临时文件写完后再替换目标文件，避免读取到半写入内容。
     */
    public synchronized BootstrapTokenDTO getOrRotateCredential() {
        BootstrapTokenDTO current = getValidCredential();
        if (current != null) {
            return current;
        }

        long expiresAt = clock.millis() + TOKEN_TTL_MILLIS;
        String token = generateToken();
        Path temporaryFile = null;
        try {
            Path parent = tokenFile.getParent();
            if (parent == null) {
                throw new IOException("bootstrap credential directory is unavailable");
            }
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, ".bootstrap.token-", ".tmp");
            Files.writeString(temporaryFile,
                    "expiresAt=" + expiresAt + System.lineSeparator()
                            + "token=" + token + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, tokenFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, tokenFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return new BootstrapTokenDTO(token, expiresAt);
        } catch (IOException exception) {
            log.warn("Local Edition bootstrap credential rotation failed");
            return null;
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException exception) {
                    log.warn("Local Edition bootstrap credential temporary file cleanup failed");
                }
            }
        }
    }

    public synchronized boolean matches(String presentedToken) {
        if (!StringUtils.hasText(presentedToken)) {
            return false;
        }
        BootstrapTokenDTO credential = getValidCredential();
        if (credential == null) {
            return false;
        }
        return MessageDigest.isEqual(
                credential.getToken().getBytes(StandardCharsets.UTF_8),
                presentedToken.getBytes(StandardCharsets.UTF_8));
    }

    /** 初始化完成后删除明文凭据；删除失败不会重新开放 setup，因为状态表已永久关闭入口。 */
    public synchronized void invalidate() {
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException exception) {
            // The durable setup latch already blocks replay. Keep a failed
            // post-commit cleanup from making a successful setup look failed.
            log.warn("Local Edition bootstrap credential cleanup failed after setup completion");
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Credential readCredential() {
        if (!Files.isRegularFile(tokenFile)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(tokenFile, StandardCharsets.UTF_8);
            String token = null;
            long expiresAt = 0L;
            for (String line : lines) {
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if ("token".equals(key)) {
                    token = value;
                } else if ("expiresAt".equals(key)) {
                    expiresAt = Long.parseLong(value);
                }
            }
            return StringUtils.hasText(token) && expiresAt > 0
                    ? new Credential(token, expiresAt) : null;
        } catch (IOException | NumberFormatException exception) {
            return null;
        }
    }

    private record Credential(String token, long expiresAt) {
    }
}
