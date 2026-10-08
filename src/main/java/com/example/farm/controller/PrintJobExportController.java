package com.example.farm.controller;

import com.example.farm.common.export.ExportDocument;
import com.example.farm.entity.dto.request.PrintJobHistoryExportRequest;
import com.example.farm.service.PrintJobHistoryExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** 下载与任务控制保持独立；业务资源路径继续归属打印任务。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/print-jobs/history")
@Tag(name = "打印历史导出")
@SecurityRequirement(name = "bearerAuth")
public class PrintJobExportController {
    private final PrintJobHistoryExportService service;

    @PostMapping("/export")
    @Operation(summary = "导出打印历史为 Excel 或 PDF",
            description = "管理员导出全部或筛选创建人，操作员只导出自己的平台任务。固定按结束时间过滤、包含边界；成功返回二进制附件，错误返回统一Result。默认XLSX最多10000条、PDF最多1000条，超限422，不截断。")
    @ApiResponse(responseCode = "200", description = "报表下载",
            content = {
                    @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            schema = @Schema(type = "string", format = "binary"))
            })
    public ResponseEntity<byte[]> export(@Valid @RequestBody PrintJobHistoryExportRequest request) {
        ExportDocument document = service.export(request);
        byte[] content = document.content();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.filename(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentLength(content.length)
                .body(content);
    }
}
