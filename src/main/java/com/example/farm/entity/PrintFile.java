package com.example.farm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 打印文件表（切片文件/G-code文件）
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Data
@TableName("farm_print_file")
@Schema(name = "FarmPrintFile", description = "打印文件表")
public class PrintFile {

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    /**
     * 父目录 ID（NULL 表示根目录）
     */
    @TableField("parent_id")
    @Schema(description = "父目录ID（NULL表示根目录）")
    private Long parentId;

    /**
     * 是否为文件夹（0-文件，1-文件夹）
     */
    @TableField("is_folder")
    @Schema(description = "是否为文件夹（0-文件，1-文件夹）")
    private Boolean isFolder;

    /**
     * 对象存储真实路径（如 farm/uploads/xxx.gcode）
     */
    @TableField("rustfs_key")
    @Schema(description = "对象存储真实路径")
    private String rustfsKey;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "安全文件名（带时间戳）")
    private String safeName;

    @Schema(description = "文件存储URL")
    private String fileUrl;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "上传用户ID")
    private Long userId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "预计打印耗时（秒）")
    private Integer estTime;

    @Schema(description = "耗材类型（如 PLA, PETG, ABS）")
    private String materialType;

    @Schema(description = "喷嘴直径（如 0.40, 0.60）")
    private BigDecimal nozzleSize;

    @Schema(description = "缩略图URL（G-code中提取的缩略图在RustFS中的地址）")
    private String thumbnailUrl;

    @Schema(description = "耗材预估重量（克）")
    private BigDecimal filamentWeight;

    @Schema(description = "耗材预估长度（米）")
    private BigDecimal filamentLength;

    @Schema(description = "喷头温度（℃）")
    private Integer nozzleTemp;

    @Schema(description = "热床温度（℃）")
    private Integer bedTemp;

    @Schema(description = "层高（mm）")
    private BigDecimal layerHeight;

    @Schema(description = "首层喷头温度（℃）")
    private Integer firstLayerNozzleTemp;

    @Schema(description = "首层热床温度（℃）")
    private Integer firstLayerBedTemp;

    @Schema(description = "首层层高（mm）")
    private BigDecimal firstLayerHeight;

    @TableField(exist = false)
    @Schema(description = "打印总次数（非持久化字段，通过统计计算）")
    private Integer printCount;

    @TableField(exist = false)
    @Schema(description = "打印成功率（非持久化字段，通过统计计算，0-100）")
    private BigDecimal successRate;
}
