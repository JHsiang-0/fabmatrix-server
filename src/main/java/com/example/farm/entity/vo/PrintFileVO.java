package com.example.farm.entity.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.example.farm.entity.PrintFile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 打印文件安全响应对象，不暴露对象存储内部 key 和安全文件名。
 */
@Data
@Schema(name = "PrintFileVO", description = "打印文件安全响应对象")
public class PrintFileVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "文件或目录 ID") private Long id;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "父目录 ID；根目录为 null") private Long parentId;
    @Schema(description = "是否为目录")
    private Boolean folder;
    @Schema(description = "是否为系统默认目录") private Boolean isDefault;
    @Schema(description = "可见性：PRIVATE 或 PUBLIC") private String visibility;
    @Schema(description = "文件原始名称") private String originalName;
    @Schema(description = "文件大小，单位字节") private Long fileSize;
    @JsonSerialize(using = ToStringSerializer.class) @Schema(description = "上传用户 ID") private Long userId;
    @Schema(description = "创建时间") private LocalDateTime createdAt;
    @Schema(description = "预计打印时长，单位秒") private Integer estTime;
    @Schema(description = "耗材类型") private String materialType;
    @Schema(description = "喷嘴直径，单位毫米") private BigDecimal nozzleSize;
    @Schema(description = "耗材重量，单位克") private BigDecimal filamentWeight;
    @Schema(description = "耗材长度，单位米") private BigDecimal filamentLength;
    @Schema(description = "喷嘴温度，单位摄氏度") private Integer nozzleTemp;
    @Schema(description = "热床温度，单位摄氏度") private Integer bedTemp;
    @Schema(description = "层高，单位毫米") private BigDecimal layerHeight;
    @Schema(description = "首层喷嘴温度，单位摄氏度") private Integer firstLayerNozzleTemp;
    @Schema(description = "首层热床温度，单位摄氏度") private Integer firstLayerBedTemp;
    @Schema(description = "首层层高，单位毫米") private BigDecimal firstLayerHeight;
    @Schema(description = "已结束任务打印次数") private Integer printCount;
    @Schema(description = "成功率，百分比 0-100") private BigDecimal successRate;
    @Schema(description = "当前用户是否可以管理目录结构") private Boolean canManage;
    @Schema(description = "当前用户是否可以上传或创建子目录") private Boolean canUpload;
    @Schema(description = "当前用户是否可以创建子目录") private Boolean canCreate;
    @Schema(description = "当前用户是否可以删除") private Boolean canDelete;
    @Schema(description = "当前用户是否可以移动") private Boolean canMove;
    @Schema(description = "当前用户是否可以创建打印任务") private Boolean canPrint;
    @Schema(description = "当前用户是否可以下载") private Boolean canDownload;
    @Schema(description = "目录层级，一级为1，二级为2") private Integer level;

    public static PrintFileVO from(PrintFile file) {
        if (file == null) {
            return null;
        }
        PrintFileVO vo = new PrintFileVO();
        vo.id = file.getId();
        vo.parentId = file.getParentId();
        // 数据库旧记录或刚上传的实体可能尚未填充默认值；对外契约始终返回布尔值。
        vo.folder = Boolean.TRUE.equals(file.getIsFolder());
        vo.isDefault = Boolean.TRUE.equals(file.getIsDefaultFolder());
        vo.visibility = file.getVisibility() == null ? "PRIVATE" : file.getVisibility();
        vo.originalName = file.getOriginalName();
        vo.fileSize = file.getFileSize();
        vo.userId = file.getUserId();
        vo.createdAt = file.getCreatedAt();
        vo.estTime = file.getEstTime();
        vo.materialType = file.getMaterialType();
        vo.nozzleSize = file.getNozzleSize();
        vo.filamentWeight = file.getFilamentWeight();
        vo.filamentLength = file.getFilamentLength();
        vo.nozzleTemp = file.getNozzleTemp();
        vo.bedTemp = file.getBedTemp();
        vo.layerHeight = file.getLayerHeight();
        vo.firstLayerNozzleTemp = file.getFirstLayerNozzleTemp();
        vo.firstLayerBedTemp = file.getFirstLayerBedTemp();
        vo.firstLayerHeight = file.getFirstLayerHeight();
        vo.printCount = file.getPrintCount();
        vo.successRate = file.getSuccessRate();
        vo.canManage = Boolean.TRUE.equals(file.getCanManage());
        vo.canUpload = Boolean.TRUE.equals(file.getCanUpload());
        vo.canCreate = Boolean.TRUE.equals(file.getCanCreate());
        vo.canDelete = Boolean.TRUE.equals(file.getCanDelete());
        vo.canMove = Boolean.TRUE.equals(file.getCanMove());
        vo.canPrint = Boolean.TRUE.equals(file.getCanPrint());
        vo.canDownload = Boolean.TRUE.equals(file.getCanDownload());
        vo.level = file.getLevel();
        return vo;
    }
}
