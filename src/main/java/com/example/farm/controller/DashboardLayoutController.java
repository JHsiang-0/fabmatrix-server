package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.service.DashboardLayoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
