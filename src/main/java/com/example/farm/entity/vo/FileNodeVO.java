package com.example.farm.entity.vo;

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
    private Long id;

    @Schema(description = "父目录 ID，根节点为 null")
    private Long parentId;

    @Schema(description = "是否为目录")
    private Boolean folder;

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
        node.name = file.getOriginalName();
        node.fileSize = node.folder ? null : file.getFileSize();
        node.materialType = node.folder ? null : file.getMaterialType();
        node.createdAt = file.getCreatedAt();
        return node;
    }
}
