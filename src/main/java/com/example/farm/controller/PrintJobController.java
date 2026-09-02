package com.example.farm.controller;

import com.example.farm.common.api.PageResult;
import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.entity.dto.request.AssignJobRequest;
import com.example.farm.entity.dto.request.ConfirmSafeRequest;
import com.example.farm.entity.dto.request.PrintJobQueryDTO;
import com.example.farm.entity.dto.request.StartPrintJobRequest;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterService;
import com.example.farm.common.utils.MoonrakerApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 打印任务管理接口。
 */
@Tag(name = "打印任务管理")
@RestController
@Slf4j
@RequestMapping("/api/v1/print-jobs")
@RequiredArgsConstructor
public class PrintJobController {

    private final PrintJobService printJobService;
    private final PrinterService printerService;
    private final MoonrakerApiClient moonrakerApiClient;

    /**
     * 查询排队中的任务。
     *
     * @return 当前队列任务列表
     */
    @Operation(summary = "获取排队中的任务队列")
    @GetMapping("/queue")
    public Result<List<PrintJob>> getQueue() {
        return Result.success(printJobService.getQueuedJobsForCurrentUser());
    }

    /**
     * 分页查询打印任务列表（支持多条件过滤）
     *
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询打印任务列表")
    @PostMapping("/page")
    public Result<PageResult<PrintJob>> queryJobs(@Valid @RequestBody PrintJobQueryDTO queryDTO) {
        return Result.success(PageResult.from(printJobService.queryJobs(queryDTO)));
    }

    /**
     * 获取单个打印任务详情
     *
     * @param id 任务ID
     * @return 任务详情
     */
    @Operation(summary = "获取打印任务详情")
    @GetMapping("/{id}")
    public Result<PrintJob> getById(@PathVariable Long id) {
        PrintJob job = printJobService.getAccessibleJob(id);
        return Result.success(job);
    }

    /**
     * 创建打印任务。
     *
     * @param req 创建参数
     * @return 新任务 ID
     * @throws BusinessException 当参数非法或关联文件不存在时抛出
     */
    @Operation(summary = "创建打印任务")
    @PostMapping("/create")
    public Result<Long> createJob(@Valid @RequestBody PrintJobCreateDTO req) {
        Long jobId = printJobService.createJob(req);
        log.info("创建打印任务请求完成: jobId={}", jobId);
        return Result.success(jobId, "任务创建成功");
    }

    /**
     * 取消任务
     * - QUEUED: 未分配打印机，直接取消
     * - ASSIGNED/READY/PAUSED: 已分配打印机，需调用 Moonraker 接口取消打印，并解绑机器
     *
     * @param id 任务 ID
     * @return 取消结果
     * @throws BusinessException 当任务状态不允许取消时抛出
     */
    @Operation(summary = "取消任务")
    @DeleteMapping("/{id}")
    public Result<String> cancelJob(@PathVariable Long id) {
        PrintJob job = printJobService.getAccessibleJob(id);

        String status = job.getStatus();
        Long printerId = job.getPrinterId();

        // 检查状态机：非法转换统一返回 HTTP 422/code=422。
        PrintJobStatus.requireTransition(status, PrintJobStatus.CANCELLED);

        // 如果任务已分配到打印机，需要先取消打印并解绑机器
        if (printerId != null) {
            Printer printer = printerService.getById(printerId);
            if (printer != null && printer.getIpAddress() != null) {
                // 调用 Moonraker 接口取消打印
                boolean cancelled = moonrakerApiClient.cancelPrint(printer.getIpAddress());
                if (!cancelled) {
                    log.warn("取消打印机上的打印任务失败: jobId={}, printerId={}, ip={}",
                            id, printerId, printer.getIpAddress());
                }
            }

            // 解绑机器：清除 currentJobId，重置 isSafeToPrint
            printer.setCurrentJobId(null);
            printer.setIsSafeToPrint(false);
            printerService.updateById(printer);
            log.info("已解绑打印机: jobId={}, printerId={}", id, printerId);
        }

        // 更新任务状态为 CANCELLED
        job.setStatus(PrintJobStatus.CANCELLED.name());
        printJobService.updateById(job);

        log.info("取消打印任务成功: jobId={}, 原状态={}", id, status);
        return Result.success(null, "任务已取消");
    }

    /**
     * 手动派发任务并立即启动打印（兼容旧接口）。
     *
     * @param jobId 任务 ID
     * @param printerId 打印机 ID
     * @return 派发结果
     * @throws BusinessException 当任务状态、打印机状态或参数不满足要求时抛出
     */
    @Operation(summary = "手动指派任务给打印机并开始（兼容模式）")
    @PostMapping("/{jobId}/assign")
    public Result<String> assignJob(@PathVariable Long jobId, @RequestParam Long printerId) {
        boolean success = printJobService.assignAndStartPrint(jobId, printerId);
        if (!success) {
            throw new BusinessException("任务派发失败，请检查任务与打印机状态");
        }
        log.info("手动派发任务成功: jobId={}, printerId={}", jobId, printerId);
        return Result.success(null, "任务已下发，打印机开始执行");
    }

    // =============================================
    // 安全打印流程接口（现场确认模式）
    // =============================================

    /**
     * 后台派发任务（两步式安全打印第一步）
     * - 将任务分配给指定打印机，状态变为 ASSIGNED
     * - 重置打印机的安全确认标记
     *
     * @param req 派发请求（jobId, printerId）
     * @return 操作结果
     */
    @Operation(summary = "派发任务（安全模式-第一步）")
    @PostMapping("/safe/assign")
    public Result<String> assignJobSafe(@Valid @RequestBody AssignJobRequest req) {
        printJobService.assignJob(req.getJobId(), req.getPrinterId());
        log.info("安全派发任务成功: jobId={}, printerId={}", req.getJobId(), req.getPrinterId());
        return Result.success(null, "任务已派发，请通知现场操作员确认安全后启动打印");
    }

    /**
     * 现场确认打印机热床已清理安全（两步式安全打印第二步之一）
     * - 操作员在现场清理完热床后调用
     * - 设置打印机的安全确认标记为 true
     *
     * @param req 确认请求（printerId, operatorId）
     * @return 操作结果
     */
    @Operation(summary = "现场确认机器安全（安全模式-第二步之一）")
    @PostMapping("/safe/confirm")
    public Result<String> confirmPrinterSafe(@Valid @RequestBody ConfirmSafeRequest req) {
        Long operatorId = SecurityContextUtil.getCurrentUserId();
        printJobService.confirmPrinterSafe(req.getPrinterId(), operatorId);
        log.info("现场确认打印机安全: printerId={}, operatorId={}", req.getPrinterId(), operatorId);
        return Result.success(null, "热床已确认安全，可以启动打印");
    }

    /**
     * 现场启动打印（两步式安全打印第二步之二）
     * - 校验任务状态为 ASSIGNED
     * - 校验打印机已确认安全
     * - 调用 Moonraker 启动打印
     *
     * @param req 启动请求（jobId, operatorId）
     * @return 操作结果
     */
    @Operation(summary = "现场启动打印（安全模式-第二步之二）")
    @PostMapping("/safe/start")
    public Result<String> startPrint(@Valid @RequestBody StartPrintJobRequest req) {
        Long operatorId = SecurityContextUtil.getCurrentUserId();
        String action = req.getAction();
        printJobService.startPrint(req.getJobId(), operatorId, action);
        String msg = "START_PRINT".equalsIgnoreCase(action) ? "打印任务已启动" : "文件已上传到机器";
        log.info("现场启动打印成功: jobId={}, operatorId={}, action={}", req.getJobId(), operatorId, action);
        return Result.success(null, msg);
    }
}
