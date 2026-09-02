package com.example.farm.entity.vo;

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

    private Long id;
    private Long parentId;
    @Schema(description = "是否为目录")
    private Boolean folder;
    private String originalName;
    private String fileUrl;
    private Long fileSize;
    private Long userId;
    private LocalDateTime createdAt;
    private Integer estTime;
    private String materialType;
    private BigDecimal nozzleSize;
    private String thumbnailUrl;
    private BigDecimal filamentWeight;
    private BigDecimal filamentLength;
    private Integer nozzleTemp;
    private Integer bedTemp;
    private BigDecimal layerHeight;
    private Integer firstLayerNozzleTemp;
    private Integer firstLayerBedTemp;
    private BigDecimal firstLayerHeight;
    private Integer printCount;
    private BigDecimal successRate;

    public static PrintFileVO from(PrintFile file) {
        if (file == null) {
            return null;
        }
        PrintFileVO vo = new PrintFileVO();
        vo.id = file.getId();
        vo.parentId = file.getParentId();
        vo.folder = file.getIsFolder();
        vo.originalName = file.getOriginalName();
        vo.fileUrl = file.getFileUrl();
        vo.fileSize = file.getFileSize();
        vo.userId = file.getUserId();
        vo.createdAt = file.getCreatedAt();
        vo.estTime = file.getEstTime();
        vo.materialType = file.getMaterialType();
        vo.nozzleSize = file.getNozzleSize();
        vo.thumbnailUrl = file.getThumbnailUrl();
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
        return vo;
    }
}
