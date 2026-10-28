package com.example.farm.common.utils;

import java.util.List;
import java.util.Locale;

/** 单农场固定岗位权限；资源归属和业务状态仍由服务层检查。 */
public final class RolePermissions {
    private RolePermissions() { }

    public static List<String> forRole(String role) {
        return switch (role == null ? "" : role.toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> List.of("printer:read", "file:read", "job:read", "file:write",
                    "job:operate", "printer:manage", "user:manage", "audit:read", "session:manage");
            case "OPERATOR" -> List.of("printer:read", "file:read", "job:read", "file:write", "job:operate");
            case "VIEWER" -> List.of("printer:read", "file:read", "job:read");
            default -> List.of();
        };
    }
}
