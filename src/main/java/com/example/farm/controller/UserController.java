package com.example.farm.controller;

import com.example.farm.common.api.PageResult;
import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.User;
import com.example.farm.entity.dto.ChangePasswordDTO;
import com.example.farm.entity.dto.LoginResultDTO;
import com.example.farm.entity.dto.PasswordMigrateResultDTO;
import com.example.farm.entity.dto.PasswordStatusResultDTO;
import com.example.farm.entity.dto.UserLoginDTO;
import com.example.farm.entity.dto.UserQueryDTO;
import com.example.farm.entity.dto.UserRegisterDTO;
import com.example.farm.entity.dto.UserUpdateDTO;
import com.example.farm.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户认证与管理接口。
 */
@Slf4j
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Value("${admin.secret-key}")
    private String adminSecretKey;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginResultDTO> login(@Valid @RequestBody UserLoginDTO loginDTO) {
        return Result.success(userService.login(loginDTO), "登录成功");
    }

    @Operation(summary = "管理员创建操作员")
    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody UserRegisterDTO registerDTO) {
        return Result.success(userService.register(registerDTO), "注册成功");
    }

    @Operation(summary = "管理员创建操作员账号")
    @PostMapping("/admin/users")
    public Result<Long> createOperator(@Valid @RequestBody UserRegisterDTO registerDTO) {
        return Result.success(userService.register(registerDTO), "操作员创建成功");
    }

    @Operation(summary = "修改密码")
    @PostMapping("/{userId}/change-password")
    public Result<String> changePassword(@PathVariable Long userId,
                                         @Valid @RequestBody ChangePasswordDTO changePasswordDTO) {
        ensureCurrentUser(userId);
        userService.changePassword(userId, changePasswordDTO);
        return Result.success(null, "密码修改成功");
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/{userId}/profile")
    public Result<User> getCurrentUser(@PathVariable Long userId) {
        ensureCurrentUser(userId);
        return Result.success(userService.getCurrentUser(userId));
    }

    @Operation(summary = "更新用户信息")
    @PutMapping("/{userId}/profile")
    public Result<String> updateUserInfo(@PathVariable Long userId,
                                         @Valid @RequestBody UserUpdateDTO updateDTO) {
        ensureCurrentUser(userId);
        updateDTO.setId(userId);
        updateDTO.setRole(null);
        userService.updateUserInfo(updateDTO);
        return Result.success(null, "信息更新成功");
    }

    @Operation(summary = "管理员查询用户列表")
    @GetMapping("/admin/users")
    public Result<PageResult<User>> pageUsers(@Valid UserQueryDTO queryDTO) {
        return Result.success(PageResult.from(userService.pageUsers(queryDTO)));
    }

    @Operation(summary = "管理员更新用户信息")
    @PutMapping("/admin/users/{userId}")
    public Result<String> adminUpdateUser(@PathVariable Long userId,
                                          @Valid @RequestBody UserUpdateDTO updateDTO) {
        updateDTO.setId(userId);
        userService.updateUserInfo(updateDTO);
        return Result.success(null, "用户信息更新成功");
    }

    @Operation(summary = "管理员禁用用户")
    @PostMapping("/admin/users/{userId}/disable")
    public Result<String> disableUser(@PathVariable Long userId) {
        Long adminId = SecurityContextUtil.getCurrentUserId();
        userService.disableUser(userId, adminId);
        return Result.success(null, "用户已禁用");
    }

    @Operation(summary = "管理员启用用户")
    @PostMapping("/admin/users/{userId}/enable")
    public Result<String> enableUser(@PathVariable Long userId) {
        Long adminId = SecurityContextUtil.getCurrentUserId();
        userService.enableUser(userId, adminId);
        return Result.success(null, "用户已启用");
    }

    @Operation(summary = "管理员批量迁移明文密码")
    @PostMapping("/admin/migrate-passwords")
    public Result<PasswordMigrateResultDTO> migrateAllPasswords(@RequestParam String adminSecret) {
        validateAdminSecret(adminSecret);
        PasswordMigrateResultDTO result = userService.migrateAllPasswords();
        return Result.success(result,
                String.format("密码迁移完成：已迁移 %d 个，跳过 %d 个", result.getMigratedCount(), result.getSkippedCount()));
    }

    @Operation(summary = "管理员检查密码存储状态")
    @GetMapping("/admin/password-status")
    public Result<PasswordStatusResultDTO> checkPasswordStatus(@RequestParam String adminSecret) {
        validateAdminSecret(adminSecret);
        return Result.success(userService.checkPasswordStatus());
    }

    @Operation(summary = "检查用户名是否可用")
    @GetMapping("/check-username")
    public Result<Boolean> checkUsername(@RequestParam String username) {
        boolean exists = userService.isUsernameExists(username);
        return Result.success(!exists, exists ? "用户名已被使用" : "用户名可用");
    }

    @Operation(summary = "检查邮箱是否可用")
    @GetMapping("/check-email")
    public Result<Boolean> checkEmail(@RequestParam String email) {
        boolean exists = userService.isEmailExists(email);
        return Result.success(!exists, exists ? "邮箱已被使用" : "邮箱可用");
    }

    private void validateAdminSecret(String adminSecret) {
        if (!adminSecretKey.equals(adminSecret)) {
            throw new BusinessException("管理员密钥错误");
        }
    }

    private void ensureCurrentUser(Long userId) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (!userId.equals(currentUserId)) {
            throw new BusinessException("只能操作自己的用户信息");
        }
    }
}
