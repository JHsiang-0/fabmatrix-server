package com.example.farm.entity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.example.farm.entity.PrintFile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件库目录树节点。
 */
@Data
@Schema(name = "FileNodeVO", description = "文件库目录树节点")
public class FileNodeVO {

    @Schema(description = "节点 ID")
    @JsonSerialize(using = ToStringSerializer.class) private Long id;

    @Schema(description = "父目录 ID，根节点为 null")
    @JsonSerialize(using = ToStringSerializer.class) private Long parentId;

    @Schema(description = "是否为目录")
    private Boolean folder;

    @Schema(description = "目录层级，一级为1，二级为2")
    private Integer level;

    @Schema(description = "是否为系统默认目录")
    private Boolean isDefault;

    @Schema(description = "目录所属用户ID")
    @JsonSerialize(using = ToStringSerializer.class) private Long userId;

    @Schema(description = "可见性：PRIVATE 或 PUBLIC")
    private String visibility;

    @Schema(description = "当前用户是否可以管理目录结构")
    private Boolean canManage;

    @Schema(description = "当前用户是否可以在目录中上传或创建子目录")
    private Boolean canUpload;

    @Schema(description = "当前用户是否可以在该目录下创建子目录")
    private Boolean canCreate;

    @Schema(description = "当前用户是否可以删除节点")
    private Boolean canDelete;

    @Schema(description = "当前用户是否可以移动节点")
    private Boolean canMove;

    @Schema(description = "目录名或文件原始名")
    private String name;

    @Schema(description = "文件大小（字节），目录为 null")
    private Long fileSize;

    @Schema(description = "耗材类型，目录为 null")
    private String materialType;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "子节点，文件节点为空数组")
    private List<FileNodeVO> children = new ArrayList<>();

    public static FileNodeVO from(PrintFile file) {
        FileNodeVO node = new FileNodeVO();
        node.id = file.getId();
        node.parentId = file.getParentId();
        node.folder = Boolean.TRUE.equals(file.getIsFolder());
        node.isDefault = Boolean.TRUE.equals(file.getIsDefaultFolder());
        node.userId = file.getUserId();
        node.visibility = file.getVisibility();
        node.canManage = Boolean.TRUE.equals(file.getCanManage());
        node.canUpload = Boolean.TRUE.equals(file.getCanUpload());
        node.canCreate = Boolean.TRUE.equals(file.getCanCreate());
        node.canDelete = Boolean.TRUE.equals(file.getCanDelete());
        node.canMove = Boolean.TRUE.equals(file.getCanMove());
        node.name = file.getOriginalName();
        node.fileSize = node.folder ? null : file.getFileSize();
        node.materialType = node.folder ? null : file.getMaterialType();
        node.createdAt = file.getCreatedAt();
        return node;
    }
}
