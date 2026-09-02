package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.entity.Printer;
import com.example.farm.service.PrinterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 打印机硬件控制接口。
 */
@RestController
@Slf4j
@RequestMapping("/api/v1/control")
@RequiredArgsConstructor
@Tag(name = "打印机控制", description = "打印机硬件控制相关接口")
public class PrinterControlController {

    private final PrinterService printerService;
    private final MoonrakerApiClient moonrakerApiClient;

    /**
     * 发送急停指令。
     *
     * @param id 打印机 ID
     * @return 急停执行结果
     * @throws BusinessException 当打印机不存在时抛出
     */
    @PostMapping("/{id}/emergency-stop")
    @Operation(summary = "发送急停指令", description = "向指定的打印机发送紧急停止命令")
    public Result<String> emergencyStop(@PathVariable Long id) {
        Printer printer = printerService.getById(id);
        if (printer == null) {
            throw new BusinessException("打印机不存在");
        }

        log.warn("收到急停请求: printerId={}, ip={}", id, printer.getIpAddress());
        boolean success = moonrakerApiClient.emergencyStop(printer.getIpAddress());
        if (!success) {
            log.warn("急停执行失败: printerId={}", id);
            throw new BusinessException("急停指令发送失败，打印机可能已离线");
        }

        log.warn("急停执行成功: printerId={}", id);
        return Result.success(null, "急停指令已发送");
    }

    /**
     * 发送暂停打印指令。
     *
     * @param id 打印机 ID
     * @return 暂停执行结果
     * @throws BusinessException 当打印机不存在或暂停指令发送失败时抛出
     */
    @Operation(summary = "暂停打印", description = "向指定的打印机发送暂停打印命令")
    @PostMapping("/{id}/pause")
    public Result<String> pausePrint(@PathVariable Long id) {
        Printer printer = printerService.getById(id);
        if (printer == null) {
            throw new BusinessException("打印机不存在");
        }

        log.info("收到暂停打印请求: printerId={}, ip={}", id, printer.getIpAddress());
        boolean success = moonrakerApiClient.pausePrint(printer.getIpAddress());
        if (!success) {
            log.warn("暂停打印执行失败: printerId={}", id);
            throw new BusinessException("暂停指令发送失败");
        }

        log.info("暂停打印执行成功: printerId={}", id);
        return Result.success(null, "暂停指令已发送");
    }
}