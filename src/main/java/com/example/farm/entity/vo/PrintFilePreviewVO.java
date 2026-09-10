package com.example.farm.entity.vo;

import com.example.farm.entity.PrintFile;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 打印文件安全预览对象，只包含已解析元数据，不包含存储地址和文件内容。
 */
@Data
@Schema(name = "PrintFilePreviewVO", description = "打印文件安全预览信息")
public class PrintFilePreviewVO {

    @Schema(description = "文件 ID") private Long id;
    @Schema(description = "可见性：PRIVATE 或 PUBLIC") private String visibility;
    @Schema(description = "上传用户 ID") private Long userId;
    @Schema(description = "是否为默认目录") private Boolean isDefault;
    @Schema(description = "目录层级") private Integer level;
    @Schema(description = "当前用户是否可以创建打印任务") private Boolean canPrint;
    @Schema(description = "当前用户是否可以下载") private Boolean canDownload;
    @Schema(description = "文件原始名称") private String originalName;
    @Schema(description = "文件大小，单位字节") private Long fileSize;
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

    public static PrintFilePreviewVO from(PrintFile file) {
        PrintFilePreviewVO vo = new PrintFilePreviewVO();
        vo.id = file.getId();
        vo.visibility = file.getVisibility() == null ? "PRIVATE" : file.getVisibility();
        vo.userId = file.getUserId();
        vo.isDefault = Boolean.TRUE.equals(file.getIsDefaultFolder());
        vo.level = file.getLevel() == null ? (file.getParentId() == null ? 1 : 2) : file.getLevel();
        vo.canPrint = Boolean.TRUE.equals(file.getCanPrint());
        vo.canDownload = Boolean.TRUE.equals(file.getCanDownload());
        vo.originalName = file.getOriginalName();
        vo.fileSize = file.getFileSize();
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
        return vo;
    }
}
