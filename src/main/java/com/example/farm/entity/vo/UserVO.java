package com.example.farm.entity.vo;

import com.example.farm.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户安全响应对象。
 *
 * <p>只包含可以返回给客户端的用户资料，避免把持久化实体直接作为接口响应。</p>
 */
@Data
@Schema(name = "UserVO", description = "用户安全响应对象，不包含 passwordHash")
public class UserVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户 ID")
    private Long id;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "角色：ADMIN 或 OPERATOR")
    private String role;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static UserVO from(User user) {
        if (user == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.id = user.getId();
        vo.username = user.getUsername();
        vo.role = user.getRole();
        vo.email = user.getEmail();
        vo.phone = user.getPhone();
        vo.createdAt = user.getCreatedAt();
        vo.updatedAt = user.getUpdatedAt();
        return vo;
    }
}
