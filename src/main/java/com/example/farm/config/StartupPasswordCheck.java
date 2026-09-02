package com.example.farm.config;

import com.example.farm.common.utils.PasswordMigrationUtil;
import com.example.farm.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时密码安全检查
 * 检测是否存在明文密码并发出警告
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupPasswordCheck implements ApplicationRunner {

    private final UserService userService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            long totalUsers = userService.count();
            if (totalUsers == 0) {
                log.info("密码安全扫描完成：系统中暂无用户");
                return;
            }

            long plainTextCount = userService.list().stream()
                    .filter(user -> !PasswordMigrationUtil.isEncrypted(user.getPasswordHash()))
                    .count();

            long encryptedCount = totalUsers - plainTextCount;

            log.info("密码安全扫描完成：共 {} 个用户，其中 {} 个已加密，{} 个为明文",
                    totalUsers, encryptedCount, plainTextCount);

            if (plainTextCount > 0) {
                log.warn("安全警告：检测到 {} 个用户使用明文密码存储", plainTextCount);
                log.warn("   建议操作：");
                log.warn("   1. 让这些用户重新登录，系统会自动迁移为加密存储");
                log.warn("   2. 或使用管理员接口 POST /api/v1/auth/admin/migrate-passwords 批量迁移");
                log.warn("   3. 或使用管理员接口 GET /api/v1/auth/admin/password-status 查看详情");
            } else {
                log.info("所有用户密码均已加密存储");
            }

        } catch (Exception e) {
            log.warn("密码安全扫描执行失败: {}", e.getMessage());
        }
    }
}
