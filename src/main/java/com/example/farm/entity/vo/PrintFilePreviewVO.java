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

    private Long id;
    private String originalName;
    private Long fileSize;
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

    public static PrintFilePreviewVO from(PrintFile file) {
        PrintFilePreviewVO vo = new PrintFilePreviewVO();
        vo.id = file.getId();
        vo.originalName = file.getOriginalName();
        vo.fileSize = file.getFileSize();
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
        return vo;
    }
}
