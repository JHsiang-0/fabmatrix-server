package com.example.farm.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * 打印任务接口的具名 OpenAPI 响应模型。
 *
 * <p>运行时仍统一返回 {@code Result<T>}；这些类型只为 SpringDoc 明确描述各端点的
 * {@code data} 结构，避免修改全局泛型响应约定。</p>
 */
public final class PrintJobOpenApiResultVO {

    private PrintJobOpenApiResultVO() {
    }

    @Data
    @Schema(name = "ResultPrintJobSummaryList", description = "打印任务队列统一响应")
    public static class SummaryListResult {

        @Schema(description = "业务状态码；成功为 200")
        private long code;

        @Schema(description = "面向用户的结果说明")
        private String message;

        @Schema(description = "当前用户可见的排队任务摘要")
        private List<PrintJobSummaryVO> data = Collections.emptyList();

        @Schema(description = "Unix epoch 毫秒时间戳")
        private long timestamp;
    }

    @Data
    @Schema(name = "PageResultPrintJobSummary", description = "打印任务摘要分页数据")
    public static class SummaryPageData {

        @Schema(description = "当前页任务摘要")
        private List<PrintJobSummaryVO> records = Collections.emptyList();

        @Schema(description = "符合条件的总记录数")
        private long total;

        @Schema(description = "当前页码，从 1 开始")
        private long pageNum;

        @Schema(description = "每页数量")
        private long pageSize;

        @Schema(description = "总页数")
        private long pages;
    }

    @Data
    @Schema(name = "ResultPagePrintJobSummary", description = "打印任务摘要分页统一响应")
    public static class SummaryPageResult {

        @Schema(description = "业务状态码；成功为 200")
        private long code;

        @Schema(description = "面向用户的结果说明")
        private String message;

        @Schema(description = "任务摘要分页数据")
        private SummaryPageData data;

        @Schema(description = "Unix epoch 毫秒时间戳")
        private long timestamp;
    }

    @Data
    @Schema(name = "ResultPrintJobDetail", description = "打印任务详情统一响应")
    public static class DetailResult {

        @Schema(description = "业务状态码；成功为 200")
        private long code;

        @Schema(description = "面向用户的结果说明")
        private String message;

        @Schema(description = "当前用户可访问的任务详情")
        private PrintJobDetailVO data;

        @Schema(description = "Unix epoch 毫秒时间戳")
        private long timestamp;
    }

    @Data
    @Schema(name = "ResultVoid", description = "无业务数据的统一成功响应")
    public static class VoidResult {

        @Schema(description = "业务状态码；成功为 200")
        private long code;

        @Schema(description = "面向用户的结果说明")
        private String message;

        @Schema(description = "成功时固定为 null", types = {"null"})
        private Object data;

        @Schema(description = "Unix epoch 毫秒时间戳")
        private long timestamp;
    }
}
