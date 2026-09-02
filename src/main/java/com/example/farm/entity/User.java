package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 农场用户表
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Getter
@Setter
@ToString
@TableName("farm_user")
@Schema(name = "FarmUser", description = "农场用户表")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @Schema(description = "主键ID")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 登录账号
     */
    @TableField("username")
    @Schema(description = "登录账号")
    private String username;

    /**
     * 加密后的密码
     */
    @TableField("password_hash")
    @Schema(description = "加密后的密码")
    private String passwordHash;

    /**
     * 角色权限：ADMIN, OPERATOR
     */
    @TableField("role")
    @Schema(description = "角色权限：ADMIN(管理员), OPERATOR(操作员)")
    private String role;

    /**
     * 邮箱
     */
    @TableField("email")
    @Schema(description = "邮箱")
    private String email;

    /**
     * 手机号
     */
    @TableField("phone")
    @Schema(description = "手机号")
    private String phone;

    /**
     * 创建时间
     */
    @TableField("created_at")
    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
