package com.example.farm.entity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 打印机分页查询请求 DTO
 */
@Data
@Schema(description = "打印机分页查询请求参数")
public class PrinterQueryDTO {

    @Schema(description = "当前页码 (默认第1页)")
    private Integer pageNum = 1;

    @Schema(description = "每页显示条数 (默认10条)")
    private Integer pageSize = 10;

    @Schema(description = "搜索关键字：打印机名称 (模糊查询)")
    private String name;

    @Schema(description = "状态筛选：IDLE(空闲), PRINTING(打印中), OFFLINE(离线), ERROR(故障)")
    private String status;
}
