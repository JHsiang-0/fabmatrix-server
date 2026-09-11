package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 打印任务详情查询对象。
 *
 * <p>当前阶段详情字段与摘要一致，单独声明类型以稳定 OpenAPI 和后续详情扩展边界。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(name = "PrintJobDetailVO", description = "打印任务可读详情")
public class PrintJobDetailVO extends PrintJobSummaryVO {

    private static final long serialVersionUID = 1L;
}
