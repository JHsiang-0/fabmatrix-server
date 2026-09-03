package com.example.farm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.entity.dto.request.PrintJobQueryDTO;
import com.example.farm.entity.dto.request.FileJobsQueryDTO;
import com.example.farm.entity.dto.request.UpdatePrintJobPriorityRequest;

import java.util.List;

/**
 * 打印任务服务接口。
 */
public interface PrintJobService extends IService<PrintJob> {

    /**
     * 提交打印任务（兼容旧接口）。
     *
     * @param fileId 文件 ID
     * @param userId 用户 ID
     * @param priority 优先级
     * @return 任务 ID
     * @throws BusinessException 当参数非法或文件不存在时抛出
     */
    Long submitJob(Long fileId, Long userId, Integer priority);

    /**
     * 查询可参与调度的任务列表。
     *
     * @return 任务列表
     */
    List<PrintJob> getQueuedJobs();

    /**
     * 由后台调度器将排队任务派发到空闲打印机。
     * <p>该方法不依赖当前登录用户，事务内会重新校验任务和打印机状态，并保证两条记录一起成功保存。</p>
     *
     * @param jobId 任务 ID
     * @param printerId 打印机 ID
     * @return 是否完成派发；任务或打印机状态已变化时返回 false
     */
    boolean assignQueuedJob(Long jobId, Long printerId);

    /**
     * 获取当前登录用户可见的排队任务。管理员可查看全部队列，操作员只能查看自己的任务。
     */
    List<PrintJob> getQueuedJobsForCurrentUser();

    /**
     * 获取当前登录用户可访问的任务，不存在或无权限时统一返回 404。
     */
    PrintJob getAccessibleJob(Long jobId);

    /**
     * 创建打印任务（用户从上下文中获取）。
     *
     * @param req 创建请求
     * @return 任务 ID
     * @throws BusinessException 当用户未登录或文件不存在时抛出
     */
    Long createJob(PrintJobCreateDTO req);

    /**
     * 创建打印任务（显式指定用户）。
     *
     * @param req 创建请求
     * @param userId 用户 ID
     * @return 任务 ID
     * @throws BusinessException 当用户未登录或文件不存在时抛出
     */
    Long createJob(PrintJobCreateDTO req, Long userId);

    /** 带客户端幂等键创建任务。 */
    Long createJob(PrintJobCreateDTO req, Long userId, String idempotencyKey);

    /**
     * 派发任务并启动打印。
     *
     * @param jobId 任务 ID
     * @param printerId 打印机 ID
     * @return 是否派发成功
     * @throws BusinessException 当任务状态、设备状态或工艺参数校验不通过时抛出
     */
    boolean assignAndStartPrint(Long jobId, Long printerId);

    // =============================================
    // 安全打印流转核心方法（现场确认模式）
    // =============================================

    /**
     * 后台派发任务（两步式安全打印的第一步）
     * - 校验：Job 必须处于 QUEUED 状态
     * - 行为：将 Job 的 printerId 设为目标机器，状态改为 ASSIGNED
     * - 行为：将目标 Printer 的 is_safe_to_print 重置为 false（防范风险）
     *
     * @param jobId     任务 ID
     * @param printerId 目标打印机 ID
     * @throws BusinessException 当任务状态非法或打印机不存在时抛出
     */
    void assignJob(Long jobId, Long printerId);

    /**
     * 现场确认打印机热床已清理安全（两步式安全打印的第二步之一）
     * - 行为：操作员在现场清理完热床后调用。将 Printer 的 is_safe_to_print 设为 true
     *
     * @param printerId  打印机 ID
     * @param operatorId 操作员 ID（可选，从安全上下文获取）
     * @throws BusinessException 当打印机不存在时抛出
     */
    void confirmPrinterSafe(Long printerId, Long operatorId);

    /**
     * 现场启动打印（两步式安全打印的第二步之二）
     * - 校验 1：Job 必须处于 ASSIGNED 或 READY 状态
     * - 校验 2：关联的 Printer 的 is_safe_to_print 必须为 true，否则抛出业务异常"热床未确认安全，禁止打印"
     * - 行为：将 Job 状态改为 PRINTING，记录传入的 operatorId
     * - 行为：调用 Moonraker 接口发送打印指令
     * - 行为：将 Printer 的 is_safe_to_print 再次置为 false，状态改为 PRINTING
     *
     * @param jobId      任务 ID
     * @param operatorId 操作员 ID（必填，记录谁启动了打印）
     * @param action     执行动作：START_PRINT（下发并打印）或 UPLOAD_ONLY（仅上传）
     * @throws BusinessException 当状态校验不通过或 Moonraker 调用失败时抛出
     */
    void startPrint(Long jobId, Long operatorId, String action);

    /**
     * 取消任务，并在任务已绑定设备时通过协议适配器取消设备上的打印。
     */
    void cancelJob(Long jobId);

    /**
     * 将失败任务重置为排队状态。
     *
     * @param jobId 任务 ID
     */
    void retryJob(Long jobId);

    /**
     * 将尚未实际打印的已派发任务解除设备绑定并重新排队。
     *
     * @param jobId 任务 ID
     */
    void requeueJob(Long jobId);

    /**
     * 修改排队任务优先级。
     *
     * @param jobId 任务 ID
     * @param request 优先级请求
     */
    void updatePriority(Long jobId, UpdatePrintJobPriorityRequest request);

    /**
     * 分页查询打印任务列表（支持多条件过滤）
     *
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    Page<PrintJob> queryJobs(PrintJobQueryDTO queryDTO);

    /**
     * 分页查询文件关联任务。
     *
     * @param fileId 文件 ID
     * @param query 分页参数
     * @return 文件关联任务
     */
    Page<PrintJob> queryJobsByFileId(Long fileId, FileJobsQueryDTO query);
}
