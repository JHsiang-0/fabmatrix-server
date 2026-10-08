package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.entity.vo.PrinterRediscoveryResultVO;
import com.example.farm.service.PrinterRediscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/printers/rediscover")
@Tag(name = "打印机管理")
@SecurityRequirement(name = "bearerAuth")
public class PrinterRediscoveryController {
    private final PrinterRediscoveryService rediscoveryService;

    @PostMapping
    @Operation(summary = "同步已登记设备 IP", description = "仅管理员；异步重新发现已启用监控的设备，不新增设备、不执行打印动作；重复进行中的请求返回同一任务")
    public Result<PrinterRediscoveryResultVO> rediscover() {
        return Result.success(rediscoveryService.startManual());
    }

    @GetMapping
    @Operation(summary = "获取设备 IP 同步进度", description = "仅管理员；返回本实例最近一次同步任务及逐台结果")
    public Result<PrinterRediscoveryResultVO> status() {
        return Result.success(rediscoveryService.getLatest());
    }
}
