package com.example.farm.common.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码迁移工具类
 * 用于检测密码格式并支持从明文平滑迁移到 BCrypt 加密
 */
@Slf4j
public class PasswordMigrationUtil {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * 判断密码是否已经是 BCrypt 加密格式
     * BCrypt 密文格式：$2a$10$... 或 $2b$12$... 等
     *
     * @param passwordHash 数据库中存储的密码
     * @return true - 已加密；false - 明文或其他格式
     */
    public static boolean isEncrypted(String passwordHash) {
        if (passwordHash == null || passwordHash.isEmpty()) {
            return false;
        }
        // BCrypt 密文以 $2a$, $2b$, $2y$ 等开头
        return passwordHash.matches("^\\$2[ayb]\\$\\d{2}\\$.*");
    }

    /**
     * 加密明文密码
     *
     * @param plainPassword 明文密码
     * @return BCrypt 加密后的密码
     */
    public static String encryptPassword(String plainPassword) {
        if (plainPassword == null) {
            return null;
        }
        return ENCODER.encode(plainPassword);
    }

    /**
     * 验证密码（支持明文和密文自动识别）
     * 如果检测到是明文存储，返回匹配结果但不会自动更新
     *
     * @param plainPassword 用户输入的明文密码
     * @param storedHash    数据库存储的密码
     * @return true - 密码匹配
     */
    public static boolean matches(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) {
            return false;
        }

        // 如果已经是 BCrypt 加密格式
        if (isEncrypted(storedHash)) {
            return ENCODER.matches(plainPassword, storedHash);
        }

        // 否则按明文比对（兼容旧数据）
        log.warn("检测到明文密码存储，建议立即迁移加密");
        return plainPassword.equals(storedHash);
    }

    /**
     * 验证密码并自动迁移
     * 如果检测到明文存储且密码匹配，返回加密后的新密码
     *
     * @param plainPassword 用户输入的明文密码
     * @param storedHash    数据库存储的密码
     * @return MigrateResult 包含匹配结果和新的加密密码（如果需要迁移）
     */
    public static MigrateResult matchesAndMigrate(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) {
            return new MigrateResult(false, null);
        }

        // 如果已经是 BCrypt 加密格式
        if (isEncrypted(storedHash)) {
            boolean matches = ENCODER.matches(plainPassword, storedHash);
            return new MigrateResult(matches, null); // 不需要迁移
        }

        // 明文比对（兼容旧数据）
        if (plainPassword.equals(storedHash)) {
            // 密码匹配，需要迁移
            String newHash = ENCODER.encode(plainPassword);
            log.info("用户密码将自动迁移为加密存储");
            return new MigrateResult(true, newHash);
        }

        return new MigrateResult(false, null);
    }

    /**
     * 密码迁移结果
     */
    public static class MigrateResult {
        private final boolean matches;
        private final String newHash; // 如果不为 null，表示需要更新为新加密密码

        public MigrateResult(boolean matches, String newHash) {
            this.matches = matches;
            this.newHash = newHash;
        }

        public boolean isMatches() {
            return matches;
        }

        public String getNewHash() {
            return newHash;
        }

        public boolean needMigration() {
            return newHash != null;
        }
    }
}
