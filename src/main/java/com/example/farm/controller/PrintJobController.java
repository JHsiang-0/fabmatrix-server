package com.example.farm.controller;

import com.example.farm.common.api.PageResult;
import com.example.farm.common.api.Result;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.entity.dto.request.AssignJobRequest;
import com.example.farm.entity.dto.request.ConfirmSafeRequest;
import com.example.farm.entity.dto.request.PrintJobQueryDTO;
import com.example.farm.entity.dto.request.StartPrintJobRequest;
import com.example.farm.entity.dto.request.UpdatePrintJobPriorityRequest;
import com.example.farm.entity.dto.request.ReconcilePrintJobRequest;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobOpenApiResultVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.DispatchPlanService;
import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.dto.request.BatchDispatchPreviewRequest;
import com.example.farm.entity.dto.request.BatchDispatchRetryPreviewRequest;
import com.example.farm.entity.vo.BatchDispatchConfirmVO;
import com.example.farm.entity.vo.DispatchPlanPreviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@SecurityRequirement(name = "bearerAuth")
@RestController
@Slf4j
@RequestMapping("/api/v1/print-jobs")
@RequiredArgsConstructor
public class PrintJobController {

    private final PrintJobService printJobService;
    private final PrinterService printerService;
    private final DispatchPlanService dispatchPlanService;

    /**
     * 查询排队中的任务。
     *
     * @return 当前队列任务列表
     */
    @Operation(summary = "获取排队中的任务队列",
            description = "返回 PrintJobSummaryVO[]；仅包含 QUEUED，权限条件在查询阶段生效，排序为 priority DESC、createdAt ASC、id ASC。关联文件、打印机和用户缺失时保留任务并返回 null 摘要字段。")
    @ApiResponse(responseCode = "200", description = "查询成功",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.SummaryListResult.class)))
    @GetMapping("/queue")
    public Result<List<PrintJobSummaryVO>> getQueue() {
        return Result.success(printJobService.getQueuedJobsForCurrentUser());
    }

    /**
     * 分页查询打印任务列表（支持多条件过滤）
     *
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    @Operation(summary = "分页查询打印任务列表",
            description = "返回 PageResult<PrintJobSummaryVO>；当前阶段保留单值 status、printerId、userId、createdAt 起止时间筛选，排序为 createdAt DESC、id DESC。TC-003 再增加多状态、文件名和完成时间语义。")
    @ApiResponse(responseCode = "200", description = "查询成功",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.SummaryPageResult.class)))
    @PostMapping("/page")
    public Result<PageResult<PrintJobSummaryVO>> queryJobs(@Valid @RequestBody PrintJobQueryDTO queryDTO) {
        queryDTO = requireBody(queryDTO);
        return Result.success(PageResult.from(printJobService.queryJobs(queryDTO)));
    }

    /**
     * 获取单个打印任务详情
     *
     * @param id 任务ID
     * @return 任务详情
     */
    @Operation(summary = "获取打印任务详情",
            description = "返回 PrintJobDetailVO，包含任务、文件、打印机和用户可读摘要。不存在或当前用户不可访问时统一返回 404。")
    @ApiResponse(responseCode = "200", description = "查询成功",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.DetailResult.class)))
    @GetMapping("/{id}")
    public Result<PrintJobDetailVO> getById(@PathVariable Long id) {
        return Result.success(printJobService.getAccessibleJobDetail(id));
    }

    /**
     * 创建打印任务。
     *
     * @param req 创建参数
     * @return 新任务 ID
     * @throws BusinessException 当参数非法或关联文件不存在时抛出
     */
    @Operation(summary = "创建打印任务", description = "标准创建地址；任务初始状态为 QUEUED")
    @PostMapping
    public Result<String> createStandardJob(@Valid @RequestBody PrintJobCreateDTO req) {
        req = requireBody(req);
        return doCreateJob(req);
    }

    /**
     * 旧版创建地址，保留给已有客户端过渡使用。
     */
    @Deprecated
    @Operation(summary = "创建打印任务（兼容地址）", description = "请迁移到 POST /api/v1/print-jobs", deprecated = true)
    @PostMapping("/create")
    public Result<String> createJob(@Valid @RequestBody PrintJobCreateDTO req) {
        req = requireBody(req);
        return doCreateJob(req);
    }

    private Result<String> doCreateJob(PrintJobCreateDTO req) {
        Long jobId = printJobService.createJob(req);
        log.info("创建打印任务请求完成: jobId={}", jobId);
        return Result.success(String.valueOf(jobId), "任务创建成功");
    }

    /**
     * 取消任务
     * - QUEUED: 未分配打印机，直接取消
     * - ASSIGNED/READY/PAUSED: 已分配打印机，需通过协议适配器取消打印，并解绑机器
     *
     * @param id 任务 ID
     * @return 取消结果
     * @throws BusinessException 当任务状态不允许取消时抛出
     */
    @Operation(summary = "取消任务",
            description = "允许 QUEUED、ASSIGNED、UPLOADING、READY、PRINTING、PAUSED；RECONCILING 必须通过 reconcile，终态不可取消。非法状态返回 422，设备命令失败返回 503；成功 data=null。")
    @DeleteMapping("/{id}")
    public Result<Void> cancelJob(@PathVariable Long id) {
        printJobService.cancelJob(id);
        return Result.success(null, "任务已取消");
    }

    /**
     * 将失败任务重新放回队列，不直接操作打印机。
     */
    @Operation(summary = "重试打印任务",
            description = "当前实现返回 data=null 并重新使用原任务记录。TC-001 目标仅允许 FAILED，创建不可变原任务的新 QUEUED 记录并返回新任务 ID 字符串；目标字段为 retryOfJobId、rootJobId、attemptNumber。")
    @PostMapping("/{id}/retry")
    public Result<Void> retryJob(@PathVariable Long id) {
        printJobService.retryJob(id);
        return Result.success(null, "任务已重新加入队列");
    }

    /**
     * 解除尚未实际打印的任务与设备绑定并重新排队。
     */
    @Operation(summary = "重新排队打印任务",
            description = "TC-001 冻结仅允许 ASSIGNED、READY，解绑设备后进入 QUEUED；非法状态返回 422，成功 data=null。")
    @PostMapping("/{id}/requeue")
    public Result<Void> requeueJob(@PathVariable Long id) {
        printJobService.requeueJob(id);
        return Result.success(null, "任务已重新排队");
    }

    @Operation(summary = "人工核对任务结果",
            description = "仅允许将 RECONCILING 任务确认成 COMPLETED、FAILED、CANCELLED 或 QUEUED；参数错误返回 400，状态错误返回 422，成功 data=null。")
    @ApiResponse(responseCode = "200", description = "核对成功，data 固定为 null",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.VoidResult.class)))
    @PostMapping("/{id}/reconcile")
    public Result<Void> reconcileJob(@PathVariable Long id,
                                     @Valid @RequestBody ReconcilePrintJobRequest request) {
        request = requireBody(request);
        printJobService.reconcileJob(id, request);
        return Result.success(null, "任务核对结果已保存");
    }

    /**
     * 修改排队任务优先级。
     */
    @Operation(summary = "修改任务优先级",
            description = "仅允许修改 QUEUED 任务；非法状态返回 422，成功 data=null。")
    @PutMapping("/{id}/priority")
    public Result<Void> updatePriority(@PathVariable Long id,
                                       @Valid @RequestBody UpdatePrintJobPriorityRequest request) {
        request = requireBody(request);
        printJobService.updatePriority(id, request);
        return Result.success(null, "任务优先级已更新");
    }

    /**
     * 手动派发任务并立即启动打印（兼容旧接口）。
     *
     * @param jobId 任务 ID
     * @param printerId 打印机 ID
     * @return 派发结果
     * @throws BusinessException 当任务状态、打印机状态或参数不满足要求时抛出
     */
    @Deprecated
    @Operation(summary = "手动指派任务给打印机并开始（已废弃兼容接口）",
            description = "当前仍保留兼容实现。新前端禁止调用；后续实施任务将拒绝此绕过安全确认的直启链路，目标错误码为 410。",
            deprecated = true)
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
    @Operation(summary = "派发任务（安全模式-第一步）",
            description = "仅执行 QUEUED→ASSIGNED 安全派发，不启动设备；成功 data=null。")
    @ApiResponse(responseCode = "200", description = "派发成功，data 固定为 null",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.VoidResult.class)))
    @PostMapping("/safe/assign")
    public Result<Void> assignJobSafe(@Valid @RequestBody AssignJobRequest req) {
        req = requireBody(req);
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
    @Operation(summary = "现场确认机器安全（安全模式-第二步之一）",
            description = "记录当前操作员的现场安全确认；成功 data=null。")
    @ApiResponse(responseCode = "200", description = "确认成功，data 固定为 null",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.VoidResult.class)))
    @PostMapping("/safe/confirm")
    public Result<Void> confirmPrinterSafe(@Valid @RequestBody ConfirmSafeRequest req) {
        req = requireBody(req);
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
    @Operation(summary = "现场启动打印（安全模式-第二步之二）",
            description = "必须经安全确认；按 action 进入 UPLOADING、READY 或 PRINTING 的现有流程，成功 data=null。")
    @ApiResponse(responseCode = "200", description = "启动成功，data 固定为 null",
            content = @Content(schema = @Schema(implementation = PrintJobOpenApiResultVO.VoidResult.class)))
    @PostMapping("/safe/start")
    public Result<Void> startPrint(@Valid @RequestBody StartPrintJobRequest req) {
        req = requireBody(req);
        Long operatorId = SecurityContextUtil.getCurrentUserId();
        String action = req.getAction();
        printJobService.startPrint(req.getJobId(), operatorId, action);
        String msg = "START_PRINT".equalsIgnoreCase(action) ? "打印任务已启动" : "文件已上传到机器";
        log.info("现场启动打印成功: jobId={}, operatorId={}, action={}", req.getJobId(), operatorId, action);
        return Result.success(null, msg);
    }

    @Operation(summary = "批量分配预览", description = "用户主动生成批量方案；不创建任务、不占用打印机、不调用设备写接口")
    @PostMapping("/batch/preview")
    public Result<DispatchPlanPreviewVO> previewBatch(@Valid @RequestBody BatchDispatchPreviewRequest request) {
        request = requireBody(request);
        return Result.success(dispatchPlanService.preview(request), "批量分配预览生成成功");
    }

    @Operation(summary = "确认并执行批量分配", description = "重新校验计划后逐项创建任务；默认不绕过安全确认直接启动")
    @PostMapping("/batch/confirm")
    public Result<BatchDispatchConfirmVO> confirmBatch(@Valid @RequestBody BatchDispatchConfirmRequest request) {
        request = requireBody(request);
        BatchDispatchConfirmVO result = dispatchPlanService.confirm(request);
        return Result.success(result, "批量分配执行完成");
    }

    @Operation(summary = "批量失败项恢复预览", description = "仅允许无任务 ID 的 RETRYABLE 明细重新生成预览；已有任务的失败项返回恢复提示")
    @PostMapping("/batch/retry-preview")
    public Result<DispatchPlanPreviewVO> retryPreviewBatch(
            @Valid @RequestBody BatchDispatchRetryPreviewRequest request) {
        request = requireBody(request);
        return Result.success(dispatchPlanService.retryPreview(request), "批量失败项恢复预览生成成功");
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        return body;
    }
}
