package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.entity.dto.DashboardLayoutSaveRequest;
import com.example.farm.entity.vo.DashboardLayoutSaveResultVO;
import com.example.farm.service.DashboardLayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 动态看板布局资源接口。 */
@RestController
@RequestMapping("/api/v1/dashboard-layout")
@RequiredArgsConstructor
@Tag(name = "动态看板布局", description = "农场级共享看板布局读取与保存")
@SecurityRequirement(name = "bearerAuth")
public class DashboardLayoutController {

    private final DashboardLayoutService dashboardLayoutService;

    @GetMapping
    @Operation(summary = "读取动态看板布局",
            description = "ADMIN/OPERATOR 可读取规范化布局、版本和服务端限制；缺失单例时创建默认 4×12 布局")
    public Result<DashboardLayoutVO> getLayout() {
        return Result.success(dashboardLayoutService.getLayout());
    }

    @PutMapping
    @Operation(summary = "原子保存动态看板布局",
            description = "仅 ADMIN 可保存；使用 expectedVersion 乐观锁，并按 REJECT/UNALLOCATE 原子处理缩容")
    public Result<DashboardLayoutSaveResultVO> saveLayout(
            @jakarta.validation.Valid @RequestBody DashboardLayoutSaveRequest request) {
        return Result.success(dashboardLayoutService.saveLayout(request));
    }
}
