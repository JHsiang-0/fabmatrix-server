package com.example.farm.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.User;
import com.example.farm.entity.dto.*;

/**
 * 用户服务接口。
 */
public interface UserService extends IService<User> {

    /**
     * 用户登录。
     *
     * @param loginDTO 登录参数
     * @return 登录结果（含 Token）
     * @throws BusinessException 当账号被锁定或账号密码错误时抛出
     */
    LoginResultDTO login(UserLoginDTO loginDTO);

    /**
     * 用户注册。
     *
     * @param registerDTO 注册参数
     * @return 用户 ID
     * @throws BusinessException 当用户名或邮箱已存在时抛出
     */
    Long register(UserRegisterDTO registerDTO);

    /**
     * 修改密码。
     *
     * @param userId 用户 ID
     * @param changePasswordDTO 修改参数
     * @throws BusinessException 当用户不存在、旧密码错误或新密码不合法时抛出
     */
    void changePassword(Long userId, ChangePasswordDTO changePasswordDTO);

    /**
     * 更新用户信息。
     *
     * @param updateDTO 更新参数
     * @throws BusinessException 当用户不存在或邮箱被占用时抛出
     */
    void updateUserInfo(UserUpdateDTO updateDTO);

    /**
     * 查询当前用户信息。
     *
     * @param userId 用户 ID
     * @return 用户信息（已脱敏）
     * @throws BusinessException 当用户不存在时抛出
     */
    User getCurrentUser(Long userId);

    /**
     * 分页查询用户。
     *
     * @param queryDTO 查询参数
     * @return 用户分页结果
     */
    IPage<User> pageUsers(UserQueryDTO queryDTO);

    /**
     * 禁用用户。
     *
     * @param userId 用户 ID
     * @param adminId 管理员 ID
     * @throws BusinessException 当用户不存在或试图禁用当前登录账号时抛出
     */
    void disableUser(Long userId, Long adminId);

    /**
     * 启用用户。
     *
     * @param userId 用户 ID
     * @param adminId 管理员 ID
     * @throws BusinessException 当用户不存在时抛出
     */
    void enableUser(Long userId, Long adminId);

    /**
     * 判断用户名是否已存在。
     *
     * @param username 用户名
     * @return `true` 表示已存在
     */
    boolean isUsernameExists(String username);

    /**
     * 判断邮箱是否已存在。
     *
     * @param email 邮箱
     * @return `true` 表示已存在
     */
    boolean isEmailExists(String email);

    /**
     * 管理员批量迁移明文密码。
     *
     * @return 迁移统计结果
     * @throws BusinessException 当迁移过程失败时抛出
     */
    PasswordMigrateResultDTO migrateAllPasswords();

    /**
     * 管理员查询密码存储状态。
     *
     * @return 密码存储统计结果
     */
    PasswordStatusResultDTO checkPasswordStatus();
}