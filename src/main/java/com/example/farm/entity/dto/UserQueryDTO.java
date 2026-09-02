package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户分页查询 DTO
 */
@Data
@Schema(description = "用户分页查询参数")
public class UserQueryDTO {

    @Schema(description = "当前页码 (默认第1页)")
    private Integer pageNum = 1;

    @Schema(description = "每页显示条数 (默认10条)")
    private Integer pageSize = 10;

    @Schema(description = "搜索关键字：用户名 (模糊查询)")
    private String username;

    @Schema(description = "角色筛选：ADMIN(管理员), OPERATOR(操作员)")
    private String role;

    @Schema(description = "邮箱")
    private String email;
}
