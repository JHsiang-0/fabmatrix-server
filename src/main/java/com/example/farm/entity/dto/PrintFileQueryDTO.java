package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 打印文件分页查询 DTO
 */
@Data
@Schema(description = "打印文件分页查询参数")
public class PrintFileQueryDTO {

    @Schema(description = "当前页码 (默认第1页)")
    private Integer pageNum = 1;

    @Schema(description = "每页显示条数 (默认10条)")
    private Integer pageSize = 10;

    @Schema(description = "搜索关键字：文件名 (模糊查询)")
    private String fileName;

    @Schema(description = "耗材类型筛选 (PLA/PETG/ABS等)")
    private String materialType;

    @Schema(description = "上传用户ID")
    private Long userId;
}
