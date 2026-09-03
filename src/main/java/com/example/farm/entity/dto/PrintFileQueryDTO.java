package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 打印文件分页查询 DTO
 */
@Data
@Schema(description = "打印文件分页查询参数")
public class PrintFileQueryDTO {

    @Schema(description = "当前页码 (默认第1页)")
    @NotNull(message = "页码不能为空")
    @Min(value = 1, message = "页码必须大于等于1")
    private Integer pageNum = 1;

    @Schema(description = "每页显示条数 (默认10条)")
    @NotNull(message = "每页数量不能为空")
    @Min(value = 1, message = "每页数量必须大于等于1")
    @Max(value = 100, message = "每页数量不能超过100")
    private Integer pageSize = 10;

    @Schema(description = "搜索关键字：文件名 (模糊查询)")
    private String fileName;

    @Schema(description = "耗材类型筛选 (PLA/PETG/ABS等)")
    private String materialType;

    @Schema(description = "上传用户ID")
    private Long userId;

    @Schema(description = "父目录 ID；不传或为 null 查询根目录")
    @Positive(message = "父目录 ID 必须为正数")
    private Long parentId;
}
