package com.example.farm.controller;

import com.example.farm.common.api.PageResult;
import com.example.farm.common.api.Result;
import com.example.farm.entity.dto.AuditLogQueryDTO;
import com.example.farm.entity.vo.AuditLogVO;
import com.example.farm.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员操作日志查询接口。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth/admin/audit-logs")
@Tag(name = "操作审计日志")
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @Operation(summary = "分页查询操作审计日志", description = "仅管理员可查询；不返回敏感请求数据")
    public Result<PageResult<AuditLogVO>> page(@Valid @ParameterObject AuditLogQueryDTO query) {
        return Result.success(PageResult.from(auditLogService.pageAuditLogs(query)));
    }
}
