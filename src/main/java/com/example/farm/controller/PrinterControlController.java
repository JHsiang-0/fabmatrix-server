package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.service.PrinterControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 打印机硬件控制接口。
 */
@RestController
@RequestMapping("/api/v1/control")
@RequiredArgsConstructor
@Tag(name = "打印机控制", description = "打印机硬件控制相关接口")
public class PrinterControlController {

    private final PrinterControlService printerControlService;

    /**
     * 发送急停指令。
     *
     * @param id 打印机 ID
     * @return 急停执行结果
     */
    @PostMapping("/{id}/emergency-stop")
    @Operation(summary = "发送急停指令", description = "向指定的打印机发送紧急停止命令")
    public Result<Void> emergencyStop(@PathVariable Long id) {
        printerControlService.emergencyStop(id);
        return Result.success(null, "急停指令已发送");
    }

    /**
     * 发送暂停打印指令。
     *
     * @param id 打印机 ID
     * @return 暂停执行结果
     */
    @Operation(summary = "暂停打印", description = "向指定的打印机发送暂停打印命令")
    @PostMapping("/{id}/pause")
    public Result<Void> pausePrint(@PathVariable Long id) {
        printerControlService.pause(id);
        return Result.success(null, "暂停指令已发送");
    }

    @Operation(summary = "恢复打印", description = "恢复指定打印机当前绑定的暂停任务")
    @PostMapping("/{id}/resume")
    public Result<Void> resumePrint(@PathVariable Long id) {
        printerControlService.resume(id);
        return Result.success(null, "恢复指令已发送");
    }

    @Operation(summary = "取消当前设备任务", description = "取消指定打印机当前绑定的农场任务并解绑设备")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancelCurrentJob(@PathVariable Long id) {
        printerControlService.cancelCurrentJob(id);
        return Result.success(null, "当前任务已取消");
    }
}
