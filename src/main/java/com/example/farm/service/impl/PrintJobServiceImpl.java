package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.LogUtil;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.common.utils.PrintFileAccess;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.entity.dto.request.PrintJobQueryDTO;
import com.example.farm.entity.dto.request.FileJobsQueryDTO;
import com.example.farm.entity.dto.request.UpdatePrintJobPriorityRequest;
import com.example.farm.entity.dto.request.ReconcilePrintJobRequest;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.entity.enums.PrintJobTimeField;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.UserMapper;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrinterIdentityVerifier;
import com.example.farm.service.PrinterExecutionService;
import com.example.farm.service.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintJobServiceImpl extends ServiceImpl<PrintJobMapper, PrintJob> implements PrintJobService {

    private static final int RRF_START_CONFIRM_ATTEMPTS = 8;
    private static final long RRF_START_CONFIRM_DELAY_MILLIS = 400L;

    private final PrintJobMapper farmPrintJobMapper;
    private final PrintFileMapper printFileMapper;
    private final PrinterService printerService;
    private final FileStorage rustFsClient;
    private final PrinterProtocolAdapterFactory adapterFactory;
    private final WebSocketEventPublisher eventPublisher;

    @Autowired(required = false)
    private UserMapper userMapper;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    /** 兼容旧单元测试；正式运行持久化设备执行会话以支持跨重启终态确认。 */
    @Autowired(required = false)
    private PrinterExecutionService printerExecutionService;

    /** 单元测试可不提供 Redis；正式运行由 Server Edition 注入并作为跨客户端占用锁。 */
    @Autowired(required = false)
    private RedisUtil redisUtil;

    @Autowired(required = false)
    private PrinterIdentityVerifier identityVerifier;

    @Override
    public PrintJobMapper getBaseMapper() {
        return farmPrintJobMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitJob(Long fileId, Long userId, Integer priority) {
        PrintFile fileRecord = validateUsableFile(fileId, userId);
        PrintJob job = new PrintJob();
        job.setFileId(fileId);
        job.setUserId(userId);
        applyCreationSnapshots(job, fileRecord, userId);
        job.setPriority(priority != null ? priority : 0);
        job.setStatus(PrintJobStatus.QUEUED.name());
        job.setProgress(BigDecimal.ZERO);
        if (!this.save(job)) {
            throw new BusinessException("提交打印任务失败：任务记录保存失败");
        }
        initializeRootJobId(job);
        eventPublisher.publishJobStatus(job);
        recordSuccess("JOB_CREATE", "JOB", job.getId(), "打印任务 #" + job.getId());
        log.info("提交打印任务成功: jobId={}, userId={}, fileId={}, priority={}", job.getId(), userId, fileId, job.getPriority());
        return job.getId();
    }

    @Override
    public List<PrintJob> getQueuedJobs() {
        return this.list(new LambdaQueryWrapper<PrintJob>()
                .in(PrintJob::getStatus, PrintJobStatus.queuedStorageValues())
                .orderByDesc(PrintJob::getPriority)
                .orderByAsc(PrintJob::getCreatedAt)
                .orderByAsc(PrintJob::getId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignQueuedJob(Long jobId, Long printerId) {
        return withPrinterLock(printerId, () -> doAssignQueuedJob(jobId, printerId));
    }

    private boolean doAssignQueuedJob(Long jobId, Long printerId) {
        PrintJob job = this.getById(jobId);
        if (job == null || !PrintJobStatus.QUEUED.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
            return false;
        }

        Printer printer = printerService.getById(printerId);
        if (printer == null || !"IDLE".equals(printer.getStatus())) {
            return false;
        }
        if (!isPrinterCommunicationReady(printer)) {
            return false;
        }
        if (printer.getCurrentJobId() != null) {
            return false;
        }

        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.ASSIGNED);
        if (!assignJobRecord(job, printer)) {
            return false;
        }

        if (!printerService.bindJobIfIdle(printerId, job.getId())) {
            throw new BusinessException("自动派发任务失败：打印机状态保存失败");
        }
        printer.setStatus("PREPARING");
        printer.setCurrentJobId(job.getId());
        printer.setIsSafeToPrint(false);

        eventPublisher.publishJobStatus(job);
        LogUtil.dataChange("任务自动派发", "FarmPrintJob", job.getId(),
                "已分配到打印机: " + printer.getName());
        return true;
    }

    @Override
    public List<PrintJobSummaryVO> getQueuedJobsForCurrentUser() {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        return farmPrintJobMapper.selectQueuedSummaries(currentUserId, SecurityContextUtil.isAdmin());
    }

    @Override
    public PrintJob getAccessibleJob(Long jobId) {
        PrintJob job = this.getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "任务不存在");
        }
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (!SecurityContextUtil.isAdmin() && !Objects.equals(job.getUserId(), currentUserId)) {
            throw new BusinessException(404, "任务不存在");
        }
        return job;
    }

    @Override
    public PrintJobDetailVO getAccessibleJobDetail(Long jobId) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        PrintJobDetailVO detail = farmPrintJobMapper.selectAccessibleDetail(
                jobId, currentUserId, SecurityContextUtil.isAdmin());
        if (detail == null) {
            throw new BusinessException(404, "任务不存在");
        }
        return detail;
    }

    @Override
    public PrintJob getByIdempotencyKey(Long userId, String idempotencyKey) {
        if (userId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return farmPrintJobMapper.selectByIdempotencyKey(userId, idempotencyKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createJob(PrintJobCreateDTO req) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        return createJob(req, currentUserId, req == null ? null : req.getIdempotencyKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createJob(PrintJobCreateDTO req, Long userId) {
        return createJob(req, userId, req == null ? null : req.getIdempotencyKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createJob(PrintJobCreateDTO req, Long userId, String idempotencyKey) {
        if (req == null) {
            throw new BusinessException("创建任务请求不能为空");
        }
        if (userId == null) {
            throw new BusinessException("用户未登录，无法创建任务");
        }
        String normalizedKey = idempotencyKey == null ? null : idempotencyKey.trim();
        if (normalizedKey != null && normalizedKey.isBlank()) {
            normalizedKey = null;
        }
        if (normalizedKey != null) {
            PrintJob existing = farmPrintJobMapper.selectByIdempotencyKey(userId, normalizedKey);
            if (existing != null) {
                Integer requestedPriority = req.getPriority() == null ? 0 : req.getPriority();
                if (!Objects.equals(existing.getFileId(), req.getFileId())
                        || !Objects.equals(existing.getPrinterId(), req.getPrinterId())
                        || !Objects.equals(existing.getPriority(), requestedPriority)) {
                    throw new BusinessException(409, "幂等键已用于其他创建请求");
                }
                return existing.getId();
            }
        }

        PrintFile fileRecord = validateUsableFile(req.getFileId(), userId);

        PrintJob job = new PrintJob();
        job.setUserId(userId);
        job.setIdempotencyKey(normalizedKey);
        job.setFileId(req.getFileId());
        applyCreationSnapshots(job, fileRecord, userId);
        job.setPriority(req.getPriority() != null ? req.getPriority() : 0);
        job.setProgress(BigDecimal.ZERO);
        job.setCreatedAt(LocalDateTime.now());

        // 状态：QUEUED（等待派发）
        job.setStatus(PrintJobStatus.QUEUED.name());

        if (!this.save(job)) {
            throw new BusinessException("创建打印任务失败：任务记录保存失败");
        }
        initializeRootJobId(job);
        if (req.getPrinterId() != null) {
            assignJob(job.getId(), req.getPrinterId());
        } else {
            eventPublisher.publishJobStatus(job);
        }
        recordSuccess("JOB_CREATE", "JOB", job.getId(), "打印任务 #" + job.getId());
        LogUtil.dataChange("创建打印任务", "FarmPrintJob", job.getId(),
                String.format("用户=%d，文件ID=%d", userId, req.getFileId()));
        return job.getId();
    }

    private PrintFile validateUsableFile(Long fileId, Long userId) {
        PrintFile fileRecord;
        try {
            fileRecord = PrintFileAccess.requireAccessible(fileId, printFileMapper::selectById,
                    userId, SecurityContextUtil.isAdmin());
        } catch (BusinessException exception) {
            log.warn("创建打印任务失败：切片文件不存在或路径无效，fileId={}, userId={}", fileId, userId);
            throw new BusinessException(404, "所选的切片文件不存在");
        }
        if (Boolean.TRUE.equals(fileRecord.getIsFolder())) {
            throw new BusinessException(422, "不能对文件夹创建打印任务");
        }
        if (!SecurityContextUtil.isAdmin()
                && !"PUBLIC".equalsIgnoreCase(fileRecord.getVisibility())
                && !Objects.equals(fileRecord.getUserId(), userId)) {
            throw new BusinessException(404, "所选的切片文件不存在");
        }
        return fileRecord;
    }

    private void applyCreationSnapshots(PrintJob job, PrintFile fileRecord, Long userId) {
        job.setFileNameSnapshot(fileRecord == null ? null : fileRecord.getOriginalName());
        job.setCreatorUsernameSnapshot(resolveUsernameSnapshot(userId));
        job.setAttemptNumber(1);
    }

    /**
     * 喷嘴和耗材属于工艺风险提示，不是任务创建或打印的硬性门禁。
     * 仅在两侧都配置且明确不一致时记录告警，避免把未配置参数误判为冲突。
     */
    private void logCapabilityMismatchWarning(PrintFile file, Printer printer, String operation, Long jobId) {
        if (file == null || printer == null) {
            return;
        }
        List<String> warnings = new ArrayList<>();
        if (file.getMaterialType() != null && !file.getMaterialType().isBlank()
                && printer.getCurrentMaterial() != null && !printer.getCurrentMaterial().isBlank()
                && !file.getMaterialType().trim().equalsIgnoreCase(printer.getCurrentMaterial().trim())) {
            warnings.add("耗材不一致(" + file.getMaterialType().trim() + " vs "
                    + printer.getCurrentMaterial().trim() + ")");
        }
        if (file.getNozzleSize() != null && printer.getNozzleSize() != null
                && file.getNozzleSize().compareTo(printer.getNozzleSize()) != 0) {
            warnings.add("喷嘴尺寸不一致(" + file.getNozzleSize() + " vs " + printer.getNozzleSize() + ")");
        }
        if (!warnings.isEmpty()) {
            log.warn("打印参数不一致警告，允许继续：operation={}, jobId={}, printerId={}, {}",
                    operation, jobId, printer.getId(), String.join("；", warnings));
        }
    }

    private String resolveUsernameSnapshot(Long userId) {
        if (userMapper != null && userId != null) {
            try {
                var user = userMapper.selectById(userId);
                if (user != null && user.getUsername() != null && !user.getUsername().isBlank()) {
                    return user.getUsername();
                }
            } catch (RuntimeException exception) {
                log.warn("读取创建人用户名失败，保留可用的认证用户名: userId={}", userId);
            }
        }
        Long currentUserId = SecurityContextUtil.getCurrentUserIdNullable();
        String currentUsername = SecurityContextUtil.getCurrentUsername();
        return Objects.equals(currentUserId, userId) && currentUsername != null && !currentUsername.isBlank()
                ? currentUsername : null;
    }

    private void initializeRootJobId(PrintJob job) {
        if (job.getId() == null || farmPrintJobMapper.initializeRootJobId(job.getId()) != 1) {
            throw new BusinessException("创建打印任务失败：任务根链初始化失败");
        }
        job.setRootJobId(job.getId());
    }

    private boolean assignJobRecord(PrintJob job, Printer printer) {
        int affected = farmPrintJobMapper.assignJobIfQueued(
                job.getId(), printer.getId(), printer.getName(), printer.getMachineNumber());
        if (affected != 1) {
            return false;
        }
        job.setPrinterId(printer.getId());
        job.setPrinterNameSnapshot(printer.getName());
        job.setMachineNumberSnapshot(printer.getMachineNumber());
        job.setStatus(PrintJobStatus.ASSIGNED.name());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignAndStartPrint(Long jobId, Long printerId) {
        return withPrinterLock(printerId, () -> doAssignAndStartPrint(jobId, printerId));
    }

    private boolean doAssignAndStartPrint(Long jobId, Long printerId) {
        PrintJob job = getAccessibleJob(jobId);
        Printer printer = printerService.getById(printerId);

        if (job == null || printer == null) {
            log.warn("派发打印任务失败：任务或打印机不存在，jobId={}, printerId={}", jobId, printerId);
            throw new BusinessException("找不到对应的任务或打印机");
        }
        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.ASSIGNED);
        if (!"IDLE".equals(printer.getStatus())) {
            log.warn("派发打印任务失败：打印机非空闲状态，jobId={}, printerId={}, status={}", jobId, printerId, printer.getStatus());
            throw new BusinessException("该打印机正在忙碌，无法派单");
        }
        requirePrinterCommunicationReady(printer);
        requireUnboundPrinter(printer);


        // 身份不符时应在任务派发和绑定写入前拒绝，不能把未下发的动作记录为结果未知。
        PrinterEndpoint deviceEndpoint = endpointOf(printer, PrinterOperation.START_PRINT);

        // 先以 CAS 进入 ASSIGNED，外部设备调用成功后才进入 PRINTING。
        if (!assignJobRecord(job, printer)) {
            throw new BusinessException(409, "任务已被其他请求派发或状态已变化");
        }
        if (!printerService.bindJobIfIdle(printerId, jobId)) {
            throw new BusinessException(409, "打印机已被其他请求占用或状态已变化");
        }
        printer.setCurrentJobId(jobId);
        printer.setStatus("PREPARING");
        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.UPLOADING);
        job.setStatus(PrintJobStatus.UPLOADING.name());
        updateJobOrThrow(job, "上传文件到打印机失败");
        eventPublisher.publishJobStatus(job);

        // 从切片文件获取工艺参数，仅记录不一致告警，不阻断打印
        PrintFile fileRecord = PrintFileAccess.requireAccessible(job.getFileId(), printFileMapper::selectById);
        if (fileRecord == null) {
            throw new BusinessException("切片文件数据缺失");
        }

        logCapabilityMismatchWarning(fileRecord, printer, "兼容派发并启动", jobId);

        String filename = fileRecord.getOriginalName();
        String safeName = fileRecord.getSafeName();

        LogUtil.bizInfo("任务派发", "任务ID", jobId, "打印机ID", printerId, "文件名", filename);

        org.springframework.core.io.Resource fileStream = rustFsClient.getFileStream(safeName);
        if (fileStream == null) {
            throw new BusinessException("无法从对象存储中读取切片文件");
        }

        adapterFactory.getAdapter(printer.getFirmwareType())
                .uploadFile(deviceEndpoint, fileStream, filename, true);

        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PRINTING);
        job.setStatus(PrintJobStatus.PRINTING.name());
        job.setStartedAt(LocalDateTime.now());
        updateJobOrThrow(job, "启动打印失败");

        printer.setStatus("PRINTING");
        updatePrinterOrThrow(printer, "启动打印失败");
        eventPublisher.publishJobStatus(job);

        LogUtil.dataChange("启动打印任务", "FarmPrintJob", job.getId(), "已分配到打印机: " + printer.getName());
        recordSuccess("JOB_ASSIGN_AND_START", "JOB", job.getId(), "打印任务 #" + job.getId());
        return true;
    }

    // =============================================
    // 安全打印流转核心方法实现（现场确认模式）
    // =============================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignJob(Long jobId, Long printerId) {
        withPrinterLock(printerId, () -> {
            doAssignJob(jobId, printerId);
            return null;
        });
    }

    private void doAssignJob(Long jobId, Long printerId) {
        PrintJob job = getAccessibleJob(jobId);
        Printer printer = printerService.getById(printerId);

        if (job == null) {
            log.warn("派发任务失败：任务不存在，jobId={}", jobId);
            throw new BusinessException("任务不存在");
        }
        if (printer == null) {
            log.warn("派发任务失败：打印机不存在，printerId={}", printerId);
            throw new BusinessException("打印机不存在");
        }

        // 校验 1：Job 必须处于 QUEUED 状态
        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.ASSIGNED);

        // 校验 2：打印机必须处于 IDLE 状态
        if (!"IDLE".equals(printer.getStatus())) {
            log.warn("派发任务失败：打印机非空闲，printerId={}, status={}", printerId, printer.getStatus());
            throw new BusinessException("打印机 [" + printer.getName() + "] 当前忙碌，无法派单");
        }
        requirePrinterCommunicationReady(printer);
        requireUnboundPrinter(printer);

        PrintFile capabilityFile = printFileMapper.selectById(job.getFileId());
        logCapabilityMismatchWarning(capabilityFile, printer, "安全派发", jobId);

        // 行为：以任务 CAS 写入设备和本次分配快照，再建立打印机侧原子绑定。
        if (!assignJobRecord(job, printer)) {
            throw new BusinessException(409, "任务已被其他请求派发或状态已变化");
        }

        // 行为：将目标 Printer 的 is_safe_to_print 重置为 false（防范风险）
        if (!printerService.bindJobIfIdle(printerId, jobId)) {
            throw new BusinessException(409, "打印机已被其他请求占用或状态已变化");
        }
        printer.setIsSafeToPrint(false);
        printer.setCurrentJobId(jobId);
        eventPublisher.publishJobStatus(job);

        LogUtil.bizInfo("任务派发（安全模式）", "任务ID", jobId, "打印机ID", printerId, "打印机名称", printer.getName());
        recordSuccess("JOB_SAFE_ASSIGN", "JOB", jobId, "打印任务 #" + jobId);
        log.info("派发任务成功（已重置安全标记）: jobId={}, printerId={}", jobId, printerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPrinterSafe(Long jobId, Long printerId, Long operatorId) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (!Objects.equals(currentUserId, operatorId)) {
            throw new BusinessException(403, "操作员身份必须来自当前登录用户");
        }
        // 现场安全确认只写入数据库，不执行设备动作。不能等待监控线程持有的设备网络锁，
        // 否则 RRF 状态查询变慢时会让确认请求长时间挂起。
        doConfirmPrinterSafe(jobId, printerId, operatorId);
    }

    private void doConfirmPrinterSafe(Long jobId, Long printerId, Long operatorId) {
        PrintJob job = getAccessibleJob(jobId);
        Printer printer = printerService.getById(printerId);

        if (printer == null) {
            log.warn("确认打印机安全失败：打印机不存在，printerId={}", printerId);
            throw new BusinessException("打印机不存在");
        }
        String jobStatus = PrintJobStatus.normalize(job.getStatus());
        if (!PrintJobStatus.ASSIGNED.name().equals(jobStatus)
                && !PrintJobStatus.READY.name().equals(jobStatus)) {
            throw new BusinessException(422, "任务当前状态不允许进行现场安全确认");
        }
        if (!Objects.equals(job.getPrinterId(), printerId)
                || !Objects.equals(printer.getCurrentJobId(), jobId)) {
            throw new BusinessException(409, "任务与打印机当前绑定不一致，请刷新后重试");
        }

        // 使用绑定条件原子更新，避免完整实体写回覆盖监控线程刚更新的实时状态。
        if (!Boolean.TRUE.equals(printer.getIsSafeToPrint())
                && !printerService.confirmSafeIfBound(printerId, jobId)) {
            throw new BusinessException(409, "任务与打印机当前绑定已变化，请刷新后重试");
        }
        printer.setIsSafeToPrint(true);

        String operatorInfo = operatorId != null ? "operatorId=" + operatorId : "operator=system";
        LogUtil.bizInfo("现场确认安全", "任务ID", jobId, "打印机ID", printerId,
                "打印机名称", printer.getName(), "操作员", operatorInfo);
        recordSuccess("JOB_SAFE_CONFIRM", "JOB", jobId, "打印任务 #" + jobId);
        log.info("现场确认打印机安全: jobId={}, printerId={}, operatorId={}", jobId, printerId, operatorId);
    }

    @Override
    public void startPrint(Long jobId, Long operatorId, String action) {
        if (operatorId == null) {
            throw new BusinessException("启动打印必须记录操作员ID");
        }
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (!Objects.equals(currentUserId, operatorId)) {
            throw new BusinessException(403, "操作员身份必须来自当前登录用户");
        }

        PrintJob initial = getAccessibleJob(jobId);
        if (initial == null || initial.getPrinterId() == null) {
            throw new BusinessException(409, "任务当前未绑定打印机，无法启动");
        }
        withPrinterLock(initial.getPrinterId(), () -> {
            doStartPrint(jobId, operatorId, action);
            return null;
        });
    }

    private void doStartPrint(Long jobId, Long operatorId, String action) {
        // 解析 action，默认 START_PRINT
        boolean startPrint = !"UPLOAD_ONLY".equalsIgnoreCase(action);

        PrintJob job = getAccessibleJob(jobId);
        if (job == null) {
            log.warn("启动打印失败：任务不存在，jobId={}", jobId);
            throw new BusinessException("任务不存在");
        }

        // 校验 1：Job 必须处于 ASSIGNED 或 READY 状态
        String normalizedStatus = PrintJobStatus.normalize(job.getStatus());
        if (!PrintJobStatus.ASSIGNED.name().equals(normalizedStatus)
                && !PrintJobStatus.READY.name().equals(normalizedStatus)) {
            log.warn("启动打印失败：任务状态不正确，jobId={}, status={}", jobId, job.getStatus());
            throw new BusinessException(422, "任务当前状态为 [" + job.getStatus() + "]，仅 ASSIGNED 或 READY 状态可启动打印");
        }
        if (!startPrint && PrintJobStatus.READY.name().equals(normalizedStatus)) {
            throw new BusinessException(409, "文件已经上传到打印机，无需重复上传");
        }

        Long printerId = job.getPrinterId();
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            log.warn("启动打印失败：打印机不存在，printerId={}", printerId);
            throw new BusinessException("打印机不存在");
        }
        requirePrinterCommunicationReady(printer);

        // 校验 2：Printer 的 is_safe_to_print 必须为 true（仅在 START_PRINT 时校验）
        if (startPrint && !Boolean.TRUE.equals(printer.getIsSafeToPrint())) {
            log.warn("启动打印失败：热床未确认安全，jobId={}, printerId={}, isSafeToPrint={}",
                    jobId, printerId, printer.getIsSafeToPrint());
            throw new BusinessException("热床未确认安全，禁止打印！请先在现场确认清理完毕后再试");
        }

        // 获取文件信息
        PrintFile fileRecord = PrintFileAccess.requireAccessible(job.getFileId(), printFileMapper::selectById);
        if (fileRecord == null) {
            throw new BusinessException("切片文件数据缺失");
        }
        logCapabilityMismatchWarning(fileRecord, printer, "现场启动", jobId);

        String filename = fileRecord.getOriginalName();
        String safeName = fileRecord.getSafeName() != null
                ? fileRecord.getSafeName()
                : filename;

        // READY 表示文件已在设备端；此时启动只发送打印命令，不重复上传。
        boolean startUploadedFile = startPrint && PrintJobStatus.READY.name().equals(normalizedStatus);
        boolean deviceActionStarted = false;
        PrinterDeviceStatus confirmedDeviceStatus = null;
        try {
            PrinterLock.ensureValid(printerId);
            PrinterEndpoint deviceEndpoint = endpointOf(printer, startPrint
                    ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE);
            var deviceAdapter = adapterFactory.getAdapter(printer.getFirmwareType());

            if (startUploadedFile) {
                deviceActionStarted = true;
                deviceAdapter.startFile(deviceEndpoint, filename);
            } else {
                org.springframework.core.io.Resource fileStream = rustFsClient.getFileStream(safeName);
                if (fileStream == null) {
                    throw new BusinessException("无法从对象存储中读取切片文件");
                }
                // 设备网络调用前记录上传阶段，但不持有数据库长事务。
                PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.UPLOADING);
                job.setStatus(PrintJobStatus.UPLOADING.name());
                job.setUpdatedAt(LocalDateTime.now());
                updateJobOrThrow(job, "上传文件到打印机失败");
                eventPublisher.publishJobStatus(job);
                deviceActionStarted = true;
                deviceAdapter.uploadFile(deviceEndpoint, fileStream, filename, startPrint);
            }
            if (startPrint && deviceEndpoint.protocolType() == PrinterProtocolType.RRF) {
                confirmedDeviceStatus = confirmRrfPrintStarted(deviceAdapter, deviceEndpoint, filename);
            }
            if (startPrint && printerExecutionService != null) {
                printerExecutionService.beginFarmExecution(
                        printerId, jobId, filename, confirmedDeviceStatus);
            }
            PrinterLock.ensureValid(printerId);
        } catch (BusinessException exception) {
            if (deviceActionStarted) {
                markReconciliation(jobId, "启动设备动作结果未知: " + summarizeDeviceException(exception));
                markPrinterActionUnknown(printer, "启动设备动作结果未知");
                throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
            }
            throw exception;
        } catch (com.example.farm.protocol.PrinterProtocolException exception) {
            if (deviceActionStarted) {
                log.error("打印机协议调用结果未知: jobId={}, printerId={}, protocol={}, reason={}",
                        jobId, printerId, printer.getFirmwareType(), summarizeDeviceException(exception), exception);
                markReconciliation(jobId, "启动设备动作结果未知: " + summarizeDeviceException(exception));
                markPrinterActionUnknown(printer, "启动设备动作结果未知");
                throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
            }
            throw exception;
        } catch (Exception exception) {
            log.error("文件上传失败：设备调用异常，jobId={}, printerId={}, ip={}",
                    jobId, printerId, printer.getIpAddress(), exception);
            if (deviceActionStarted) {
                markReconciliation(jobId, "启动设备动作结果未知: " + summarizeDeviceException(exception));
                markPrinterActionUnknown(printer, "启动设备动作结果未知");
                throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
            }
            throw new BusinessException("机器连接失败，请检查网络：" + exception.getMessage());
        }

        // 根据 action 在一个新的短事务中落库；此时设备网络调用已经结束。
        try {
            executeShortTransaction(() -> {
                if (startPrint) {
                    PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PRINTING);
                    // 行为：将 Job 状态改为 PRINTING，记录 operatorId
                    job.setStatus(PrintJobStatus.PRINTING.name());
                    job.setOperatorId(operatorId);
                    job.setOperatorUsernameSnapshot(resolveUsernameSnapshot(operatorId));
                    job.setStartedAt(LocalDateTime.now());
                    updateJobOrThrow(job, "启动打印失败");

                    // 行为：将 Printer 的 is_safe_to_print 再次置为 false，状态改为 PRINTING
                    printer.setStatus("PRINTING");
                    printer.setIsSafeToPrint(false);
                    updatePrinterOrThrow(printer, "启动打印失败");
                    eventPublisher.publishJobStatus(job);

                    recordSuccess("JOB_START", "JOB", jobId, "打印任务 #" + jobId);
                    LogUtil.bizInfo("现场启动打印", "任务ID", jobId, "打印机ID", printerId, "操作员ID", operatorId, "文件名", filename);
                    log.info("现场启动打印成功: jobId={}, printerId={}, operatorId={}", jobId, printerId, operatorId);
                } else {
                    // UPLOAD_ONLY: 仅上传文件，状态改为 READY（就绪待机）
                    if (!PrintJobStatus.READY.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
                        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.READY);
                    }
                    job.setStatus(PrintJobStatus.READY.name());
                    updateJobOrThrow(job, "上传文件到打印机失败");

                    // 打印机状态保持 IDLE（等待手动在机器上点击打印）
                    printer.setIsSafeToPrint(false);
                    updatePrinterOrThrow(printer, "上传文件到打印机失败");
                    eventPublisher.publishJobStatus(job);

                    recordSuccess("JOB_UPLOAD", "JOB", jobId, "打印任务 #" + jobId);
                    LogUtil.bizInfo("文件上传到机器（待机）", "任务ID", jobId, "打印机ID", printerId, "操作员ID", operatorId, "文件名", filename);
                    log.info("文件已上传到机器（待机）: jobId={}, printerId={}, operatorId={}", jobId, printerId, operatorId);
                }
                return null;
            });
        } catch (Exception exception) {
            log.error("设备动作完成后状态落库失败: jobId={}, printerId={}, protocol={}",
                    jobId, printerId, printer.getFirmwareType(), exception);
            markReconciliation(jobId, "设备已接受动作但状态落库失败: " + summarizeDeviceException(exception));
            markPrinterActionUnknown(printer, "设备已接受动作但状态落库失败");
            throw new BusinessException(503, "设备已接受动作，但任务状态保存失败，已进入人工核对");
        }
    }

    private PrinterDeviceStatus confirmRrfPrintStarted(com.example.farm.protocol.PrinterProtocolAdapter adapter,
                                                       PrinterEndpoint endpoint, String expectedFilename) {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= RRF_START_CONFIRM_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                sleepBeforeStartConfirmation();
            }
            try {
                PrinterDeviceStatus status = adapter.getStatus(endpoint);
                if (status != null
                        && (status.status() == PrinterStatus.PRINTING || status.status() == PrinterStatus.PAUSED)
                        && isExpectedDeviceFile(status.filename(), expectedFilename)) {
                    return status;
                }
                String observedState = status == null ? "无响应" : status.rawState();
                String observedFile = status == null ? null : status.filename();
                lastFailure = new IllegalStateException(
                        "RRF 未确认进入打印状态，state=" + observedState + ", file=" + observedFile);
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        throw new BusinessException(503, lastFailure == null
                ? "RRF 未确认打印已启动"
                : "RRF 未确认打印已启动: " + summarizeDeviceException(lastFailure));
    }

    private boolean isExpectedDeviceFile(String observedFilename, String expectedFilename) {
        if (observedFilename == null || observedFilename.isBlank()) {
            return true;
        }
        return deviceFileBasename(observedFilename).equalsIgnoreCase(deviceFileBasename(expectedFilename));
    }

    private String deviceFileBasename(String filename) {
        if (filename == null) return "";
        String normalized = filename.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private void sleepBeforeStartConfirmation() {
        try {
            Thread.sleep(RRF_START_CONFIRM_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "等待 RRF 启动确认时被中断");
        }
    }

    private <T> T executeShortTransaction(Supplier<T> action) {
        if (transactionManager == null) {
            return action.get();
        }
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    @Override
    public void cancelJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        if (job.getPrinterId() != null) {
            withPrinterLock(job.getPrinterId(), () -> {
                doCancelJob(jobId);
                return null;
            });
            return;
        }
        doCancelJob(jobId);
    }

    private void doCancelJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        String status = PrintJobStatus.normalize(job.getStatus());
        if (PrintJobStatus.CANCELLED.name().equals(status)) {
            return;
        }
        PrintJobStatus.requireTransition(status, PrintJobStatus.CANCELLED);

        Long printerId = job.getPrinterId();
        if (printerId != null) {
            if (!PrinterLock.isHeld(printerId)) {
                withPrinterLock(printerId, () -> {
                    doCancelJob(jobId);
                    return null;
                });
                return;
            }
            Printer printer = printerService.getById(printerId);
            if (printer == null) {
                throw new BusinessException(404, "关联打印机不存在");
            }
            if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
                throw new BusinessException(10001, "打印机没有可用的网络地址");
            }
            if (!PrintJobStatus.ASSIGNED.name().equals(status)
                    && !PrintJobStatus.READY.name().equals(status)) {
                PrinterEndpoint endpoint = endpointOf(printer, PrinterOperation.CANCEL);
                PrinterLock.ensureValid(printerId);
                boolean deviceActionStarted = false;
                try {
                    var deviceAdapter = adapterFactory.getAdapter(printer.getFirmwareType());
                    deviceActionStarted = true;
                    deviceAdapter.cancel(endpoint);
                    PrinterLock.ensureValid(printerId);
                } catch (RuntimeException exception) {
                    if (!deviceActionStarted) {
                        throw exception;
                    }
                    markReconciliation(jobId, "取消设备动作结果未知: " + summarizeDeviceException(exception));
                    throw new BusinessException(503, "设备动作结果未知，任务已进入人工核对");
                }
            }
            if (!printerService.clearJobBinding(printerId, jobId)) {
                throw new BusinessException("取消打印任务失败：打印机状态保存失败");
            }
            printer.setCurrentJobId(null);
            printer.setIsSafeToPrint(false);
            printer.setStatus("IDLE");
        }

        job.setStatus(PrintJobStatus.CANCELLED.name());
        if (printerId == null && farmPrintJobMapper.updateStatusIfCurrent(
                jobId, job.getStatus(), PrintJobStatus.CANCELLED.name(), "用户取消") != 1) {
            throw new BusinessException(409, "任务已被其他请求处理或状态已变化");
        }
        if (printerId != null) {
            updateJobAndPublish(job);
        } else {
            eventPublisher.publishJobStatus(job);
        }
        recordSuccess("JOB_CANCEL", "JOB", jobId, "打印任务 #" + jobId);
        log.info("取消打印任务成功: jobId={}, 原状态={}", jobId, status);
    }

    @Override
    public Long retryJob(Long jobId) {
        if (jobId == null) {
            recordRetryFailure(null, "JOB_NOT_FOUND");
            throw new BusinessException(404, "任务不存在");
        }
        try {
            try {
                return executeRetryTransaction(() -> retryJobInTransaction(jobId));
            } catch (RetryReplayRequiredException exception) {
                // 唯一键已由并发事务命中但 RR 快照仍不可见：换新事务取得提交后的当前状态。
                return executeRetryTransaction(() -> replayRetryAfterConcurrentInsert(jobId));
            }
        } catch (RetryRejectedException exception) {
            // 此处业务事务已结束，local 单连接可安全执行 REQUIRES_NEW 失败审计。
            recordRetryFailure(jobId, exception.getAuditCode());
            throw exception;
        }
    }

    private Long executeRetryTransaction(Supplier<Long> action) {
        if (transactionManager == null) {
            return action.get();
        }
        return new TransactionTemplate(transactionManager).execute(status -> action.get());
    }

    private Long retryJobInTransaction(Long jobId) {
        PrintJob source;
        try {
            source = getAccessibleJob(jobId);
        } catch (BusinessException exception) {
            if (exception.getCode() == 404) {
                throw retryRejected(404, "任务不存在", "JOB_NOT_FOUND");
            }
            throw exception;
        }

        // MySQL 的 FOR UPDATE 是当前读：同一来源的跨实例请求会串行，并看到已提交 child。
        farmPrintJobMapper.lockRetrySource(jobId);
        PrintJob existing = farmPrintJobMapper.selectRetryChild(jobId);
        if (existing != null) {
            return replayRetryChild(source, existing);
        }
        if (!PrintJobStatus.FAILED.name().equals(PrintJobStatus.normalize(source.getStatus()))) {
            throw retryRejected(422, "只有 FAILED 状态的任务可以重试", "JOB_NOT_FAILED");
        }

        PrintFile sourceFile;
        try {
            sourceFile = validateUsableFile(source.getFileId(), source.getUserId());
        } catch (BusinessException exception) {
            throw retryRejected(422, "重试源文件不存在或不可用", "RETRY_SOURCE_UNAVAILABLE");
        }
        if (sourceFile == null || Boolean.TRUE.equals(sourceFile.getIsFolder())) {
            throw retryRejected(422, "重试源文件不存在或不可用", "RETRY_SOURCE_UNAVAILABLE");
        }

        PrintJob child = buildRetryChild(source);
        int inserted = farmPrintJobMapper.insertRetryIfAbsent(child);
        if (inserted == 0) {
            existing = farmPrintJobMapper.selectRetryChild(jobId);
            if (existing != null) {
                return replayRetryChild(source, existing);
            }
            existing = farmPrintJobMapper.selectRetryChildByLineage(
                    child.getRootJobId(), child.getAttemptNumber());
            if (existing != null) {
                throw retryRejected(409, "重试任务链数据冲突", "RETRY_LINEAGE_CONFLICT");
            }
            throw new RetryReplayRequiredException();
        }
        if (inserted != 1 || child.getId() == null) {
            throw retryRejected(5001, "创建重试任务失败：数据库暂不可用", "RETRY_DATABASE_ERROR");
        }
        recordSuccess("JOB_RETRY_CREATE", "JOB", child.getId(), "打印任务 #" + child.getId());
        eventPublisher.publishJobStatus(child);
        log.info("创建重试打印任务成功: sourceJobId={}, childJobId={}, attemptNumber={}",
                jobId, child.getId(), child.getAttemptNumber());
        return child.getId();
    }

    private Long replayRetryAfterConcurrentInsert(Long jobId) {
        PrintJob source;
        try {
            source = getAccessibleJob(jobId);
        } catch (BusinessException exception) {
            if (exception.getCode() == 404) {
                throw retryRejected(404, "任务不存在", "JOB_NOT_FOUND");
            }
            throw exception;
        }
        farmPrintJobMapper.lockRetrySource(jobId);
        PrintJob existing = farmPrintJobMapper.selectRetryChild(jobId);
        if (existing != null) {
            return replayRetryChild(source, existing);
        }
        Long expectedRoot = source.getRootJobId() == null ? source.getId() : source.getRootJobId();
        int expectedAttempt = (source.getAttemptNumber() == null ? 1 : source.getAttemptNumber()) + 1;
        existing = farmPrintJobMapper.selectRetryChildByLineage(expectedRoot, expectedAttempt);
        if (existing != null) {
            throw retryRejected(409, "重试任务链数据冲突", "RETRY_LINEAGE_CONFLICT");
        }
        throw retryRejected(5001, "创建重试任务失败：数据库暂不可用", "RETRY_DATABASE_ERROR");
    }

    private PrintJob buildRetryChild(PrintJob source) {
        PrintJob child = new PrintJob();
        child.setUserId(source.getUserId());
        child.setFileId(source.getFileId());
        child.setPriority(source.getPriority() == null ? 0 : source.getPriority());
        child.setProgress(BigDecimal.ZERO);
        child.setStatus(PrintJobStatus.QUEUED.name());
        child.setFileNameSnapshot(source.getFileNameSnapshot());
        child.setCreatorUsernameSnapshot(source.getCreatorUsernameSnapshot());
        child.setRetryOfJobId(source.getId());
        child.setRootJobId(source.getRootJobId() == null ? source.getId() : source.getRootJobId());
        child.setAttemptNumber((source.getAttemptNumber() == null ? 1 : source.getAttemptNumber()) + 1);
        child.setIdempotencyKey(null);
        child.setCreatedAt(LocalDateTime.now());
        return child;
    }

    private Long replayRetryChild(PrintJob source, PrintJob child) {
        Long expectedRoot = source.getRootJobId() == null ? source.getId() : source.getRootJobId();
        int expectedAttempt = (source.getAttemptNumber() == null ? 1 : source.getAttemptNumber()) + 1;
        if (!Objects.equals(child.getRetryOfJobId(), source.getId())
                || !Objects.equals(child.getRootJobId(), expectedRoot)
                || !Objects.equals(child.getUserId(), source.getUserId())
                || !Objects.equals(child.getFileId(), source.getFileId())
                || !Objects.equals(child.getAttemptNumber(), expectedAttempt)) {
            throw retryRejected(409, "重试任务链数据冲突", "RETRY_LINEAGE_CONFLICT");
        }
        recordSuccess("JOB_RETRY_REPLAY", "JOB", child.getId(), "打印任务 #" + child.getId());
        return child.getId();
    }

    private void recordRetryFailure(Long jobId, String errorCode) {
        if (auditLogService != null) {
            try {
                auditLogService.recordFailure("JOB_RETRY_REJECT", "JOB", jobId,
                        "打印任务重试", errorCode);
            } catch (RuntimeException exception) {
                // @Transactional 代理可能在进入 recordFailure 方法前失败，不能覆盖稳定业务错误。
                log.warn("重试拒绝审计写入失败: jobId={}, errorCode={}", jobId, errorCode, exception);
            }
        }
    }

    private RetryRejectedException retryRejected(long code, String message, String auditCode) {
        return new RetryRejectedException(code, message, auditCode);
    }

    private static final class RetryRejectedException extends BusinessException {
        private final String auditCode;

        private RetryRejectedException(long code, String message, String auditCode) {
            super(code, message);
            this.auditCode = auditCode;
        }

        private String getAuditCode() {
            return auditCode;
        }
    }

    private static final class RetryReplayRequiredException extends RuntimeException {
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requeueJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        if (job.getPrinterId() != null) {
            withPrinterLock(job.getPrinterId(), () -> {
                doRequeueJob(jobId);
                return null;
            });
            return;
        }
        doRequeueJob(jobId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileJob(Long jobId, ReconcilePrintJobRequest request) {
        if (request == null || request.getResult() == null || request.getResult().isBlank()) {
            throw new BusinessException(400, "核对结果不能为空");
        }
        PrintJob current = getAccessibleJob(jobId);
        if (current.getPrinterId() != null) {
            withPrinterLock(current.getPrinterId(), () -> {
                doReconcileJob(jobId, request);
                return null;
            });
            return;
        }
        doReconcileJob(jobId, request);
    }

    private void doReconcileJob(Long jobId, ReconcilePrintJobRequest request) {
        PrintJob job = getAccessibleJob(jobId);
        if (!PrintJobStatus.RECONCILING.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
            throw new BusinessException(422, "只有 RECONCILING 状态的任务可以人工核对");
        }

        PrintJobStatus target;
        try {
            target = PrintJobStatus.valueOf(request.getResult().trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "核对结果不支持");
        }
        PrintJobStatus.requireTransition(job.getStatus(), target);

        if (target == PrintJobStatus.QUEUED) {
            clearBindingBeforeRequeue(job);
            job.setPrinterId(null);
            job.setOperatorId(null);
            job.setStartedAt(null);
            job.setCompletedAt(null);
            job.setProgress(BigDecimal.ZERO);
            job.setErrorReason(request.getReason());
        } else {
            clearBindingAfterReconcile(job);
            job.setCompletedAt(LocalDateTime.now());
            if (target == PrintJobStatus.COMPLETED) {
                job.setProgress(BigDecimal.valueOf(100));
                job.setErrorReason(null);
            } else {
                job.setErrorReason(request.getReason());
            }
        }
        job.setStatus(target.name());
        updateJobAndPublish(job);
        recordSuccess("JOB_RECONCILE", "JOB", jobId, "打印任务 #" + jobId);
        log.info("人工核对打印任务完成: jobId={}, result={}", jobId, target);
    }

    private void clearBindingAfterReconcile(PrintJob job) {
        Long printerId = job.getPrinterId();
        if (printerId == null) {
            return;
        }
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            log.warn("人工核对任务关联打印机已不存在，保留历史 printerId: jobId={}, printerId={}",
                    job.getId(), printerId);
            return;
        }
        if (printer.getCurrentJobId() != null && !Objects.equals(printer.getCurrentJobId(), job.getId())) {
            throw new BusinessException(409, "打印机当前绑定其他任务");
        }
        if (Objects.equals(printer.getCurrentJobId(), job.getId())
                && !printerService.clearJobBinding(printerId, job.getId())) {
            throw new BusinessException("人工核对失败：打印机绑定清理失败");
        }
        if ("OFFLINE".equalsIgnoreCase(printer.getConnectionStatus())) {
            Printer healthPreservingStatus = new Printer();
            healthPreservingStatus.setId(printerId);
            healthPreservingStatus.setStatus("OFFLINE");
            printerService.updateById(healthPreservingStatus);
        }
    }

    private void doRequeueJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        String status = PrintJobStatus.normalize(job.getStatus());
        if (PrintJobStatus.QUEUED.name().equals(status)) {
            return;
        }
        if (!(PrintJobStatus.ASSIGNED.name().equals(status)
                || PrintJobStatus.READY.name().equals(status))) {
            throw new BusinessException(422, "只有已派发或已就绪任务可以重新排队");
        }

        Long printerId = job.getPrinterId();
        if (printerId != null) {
            Printer printer = printerService.getById(printerId);
            if (printer == null) {
                throw new BusinessException(404, "关联打印机不存在");
            }
            if (printer.getCurrentJobId() != null && !Objects.equals(printer.getCurrentJobId(), jobId)) {
                throw new BusinessException(409, "打印机当前绑定其他任务");
            }
            if (Objects.equals(printer.getCurrentJobId(), jobId)) {
                printer.setCurrentJobId(null);
                printer.setIsSafeToPrint(false);
                if ("PREPARING".equals(printer.getStatus())) {
                    printer.setStatus("IDLE");
                }
                if (!printerService.clearJobBinding(printer.getId(), jobId)) {
                    throw new BusinessException("重新排队任务失败：打印机状态保存失败");
                }
            }
        }

        PrintJobStatus.requireTransition(status, PrintJobStatus.QUEUED);
        job.setPrinterId(null);
        job.setOperatorId(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setErrorReason(null);
        job.setProgress(BigDecimal.ZERO);
        job.setStatus(PrintJobStatus.QUEUED.name());
        updateJobAndPublish(job);
        recordSuccess("JOB_REQUEUE", "JOB", jobId, "打印任务 #" + jobId);
        log.info("重新排队打印任务成功: jobId={}, 原状态={}", jobId, status);
    }

    private void clearBindingBeforeRequeue(PrintJob job) {
        Long printerId = job.getPrinterId();
        if (printerId == null) {
            return;
        }
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            // 任务可能保留了已删除设备的历史 ID；设备已不存在时没有运行时绑定可释放，
            // 允许失败任务重新入队，并通过日志保留该数据修复线索。
            log.warn("重试任务发现关联打印机已不存在，将清除历史设备 ID: jobId={}, printerId={}",
                    job.getId(), printerId);
            return;
        }
        if (printer.getCurrentJobId() != null && !Objects.equals(printer.getCurrentJobId(), job.getId())) {
            throw new BusinessException(409, "打印机当前绑定其他任务");
        }
        if (Objects.equals(printer.getCurrentJobId(), job.getId())
                && !printerService.clearJobBinding(printerId, job.getId())) {
            throw new BusinessException("重新排队任务失败：打印机状态保存失败");
        }
        preserveOfflineBusinessStatus(printer);
    }

    private void preserveOfflineBusinessStatus(Printer printer) {
        if (!"OFFLINE".equalsIgnoreCase(printer.getConnectionStatus())) {
            return;
        }
        Printer update = new Printer();
        update.setId(printer.getId());
        update.setStatus("OFFLINE");
        printerService.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePriority(Long jobId, UpdatePrintJobPriorityRequest request) {
        PrintJob job = getAccessibleJob(jobId);
        String status = PrintJobStatus.normalize(job.getStatus());
        if (!PrintJobStatus.QUEUED.name().equals(status)) {
            throw new BusinessException(422, "只有排队中的任务可以修改优先级");
        }
        if (request == null || request.getPriority() == null) {
            throw new BusinessException(400, "任务优先级不能为空");
        }
        job.setPriority(request.getPriority());
        if (!this.updateById(job)) {
            throw new BusinessException("任务优先级更新失败");
        }
        recordSuccess("JOB_PRIORITY_UPDATE", "JOB", jobId, "打印任务 #" + jobId);
        log.info("修改打印任务优先级成功: jobId={}, priority={}", jobId, request.getPriority());
    }

    private void updateJobAndPublish(PrintJob job) {
        updateJobOrThrow(job, "任务状态更新失败");
        eventPublisher.publishJobStatus(job);
    }

    private void updateJobOrThrow(PrintJob job, String operation) {
        if (!this.updateById(job)) {
            throw new BusinessException(operation + "：任务状态保存失败");
        }
    }

    private void updatePrinterOrThrow(Printer printer, String operation) {
        if (!printerService.updateById(printer)) {
            throw new BusinessException(operation + "：打印机状态保存失败");
        }
    }

    private void recordSuccess(String action, String targetType, Object targetId, String targetLabel) {
        if (auditLogService != null) {
            auditLogService.recordSuccess(action, targetType, targetId, targetLabel);
        }
    }

    private boolean isPrinterCommunicationReady(Printer printer) {
        if (printer == null || Boolean.FALSE.equals(printer.getMonitorEnabled())) {
            return false;
        }
        String connectionStatus = printer.getConnectionStatus();
        // 兼容尚未迁移通信字段的历史测试/数据；正式数据会明确写入 ONLINE/OFFLINE/UNKNOWN。
        return connectionStatus == null || connectionStatus.isBlank()
                || "ONLINE".equalsIgnoreCase(connectionStatus);
    }

    private void requirePrinterCommunicationReady(Printer printer) {
        if (isPrinterCommunicationReady(printer)) {
            return;
        }
        log.warn("设备当前不可用，拒绝任务操作: printerId={}, monitorEnabled={}, connectionStatus={}, businessStatus={}",
                printer == null ? null : printer.getId(),
                printer == null ? null : printer.getMonitorEnabled(),
                printer == null ? null : printer.getConnectionStatus(),
                printer == null ? null : printer.getStatus());
        throw new BusinessException(409, "打印机当前离线或状态未知，请恢复通信后再操作");
    }

    private void requireUnboundPrinter(Printer printer) {
        if (printer.getCurrentJobId() != null) {
            log.warn("派发任务失败：打印机仍绑定任务，printerId={}, currentJobId={}",
                    printer.getId(), printer.getCurrentJobId());
            throw new BusinessException(409, "打印机当前已绑定任务，无法派发");
        }
    }

    private <T> T withPrinterLock(Long printerId, Supplier<T> action) {
        return PrinterLock.execute(printerId, redisUtil, action);
    }

    @Override
    public void markReconciliation(Long jobId, String reason) {
        if (jobId == null) return;
        java.util.concurrent.atomic.AtomicBoolean stateChanged = new java.util.concurrent.atomic.AtomicBoolean(false);
        Runnable action = () -> {
            PrintJob current = this.getById(jobId);
            if (current == null) return;
            String currentStatus = PrintJobStatus.normalize(current.getStatus());
            if (!java.util.Set.of("ASSIGNED", "UPLOADING", "READY", "PRINTING", "PAUSED")
                    .contains(currentStatus)) {
                return;
            }
            String normalizedReason = reason == null || reason.isBlank()
                    ? "设备动作结果未知，请人工核对" : reason;
            String safeReason = normalizedReason.length() > 255
                    ? normalizedReason.substring(0, 255) : normalizedReason;
            if (farmPrintJobMapper.updateStatusIfCurrent(
                    jobId, currentStatus, PrintJobStatus.RECONCILING.name(), safeReason) == 1) {
                current.setStatus(PrintJobStatus.RECONCILING.name());
                current.setErrorReason(safeReason);
                eventPublisher.publishJobStatus(current);
                stateChanged.set(true);
            }
        };
        if (transactionManager == null) {
            action.run();
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> action.run());
        }
        // Windows Local Edition 的 SQLite 连接池只有一个连接。必须等待上面的
        // REQUIRES_NEW 状态事务释放连接后，再开启失败审计自己的新事务。
        if (stateChanged.get() && auditLogService != null) {
            auditLogService.recordFailure("JOB_RECONCILE_REQUIRED", "JOB", jobId,
                    "打印任务 #" + jobId, "DEVICE_ACTION_UNKNOWN");
        }
    }

    private String summarizeDeviceException(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void markPrinterActionUnknown(Printer printer, String reason) {
        if (printer == null || printer.getId() == null) return;
        Runnable action = () -> {
            Printer update = new Printer();
            update.setId(printer.getId());
            update.setStatus("ERROR");
            update.setIsSafeToPrint(false);
            printerService.updateById(update);
        };
        if (transactionManager == null) {
            action.run();
        } else {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.executeWithoutResult(status -> action.run());
        }
        log.warn("设备动作结果未知，打印机进入 ERROR 并保留绑定: printerId={}, reason={}",
                printer.getId(), reason);
    }

    private PrinterEndpoint endpointOf(Printer printer, PrinterOperation operation) {
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(printer.getFirmwareType());
        if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
            throw new BusinessException(10001, "打印机没有可用的网络地址");
        }
        PrinterLock.ensureValid(printer.getId());
        if (identityVerifier != null) {
            identityVerifier.verifyFresh(printer);
        }
        return new PrinterEndpoint(printer.getId(), printer.getIpAddress(), printer.getApiKey(), protocolType);
    }

    @Override
    public Page<PrintJobSummaryVO> queryJobs(PrintJobQueryDTO queryDTO) {
        if (queryDTO == null) {
            throw new BusinessException(400, "任务查询参数不能为空");
        }
        if (queryDTO.getStartTime() != null && queryDTO.getEndTime() != null
                && queryDTO.getStartTime().isAfter(queryDTO.getEndTime())) {
            throw new BusinessException(400, "开始时间不能晚于结束时间");
        }
        List<String> statuses = normalizeQueryStatuses(queryDTO);
        String fileName = normalizeFileName(queryDTO.getFileName());
        int pageNum = queryDTO.getPageNum() != null ? queryDTO.getPageNum() : 1;
        int pageSize = queryDTO.getPageSize() != null ? queryDTO.getPageSize() : 10;

        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        Page<PrintJobSummaryVO> page = farmPrintJobMapper.selectPageSummaries(
                new Page<>(pageNum, pageSize), statuses, queryDTO.getPrinterId(),
                queryDTO.getUserId(), currentUserId, SecurityContextUtil.isAdmin(),
                fileName, queryDTO.getTimeField() == null ? PrintJobTimeField.CREATED_AT : queryDTO.getTimeField(),
                queryDTO.getStartTime(), queryDTO.getEndTime());
        page.getRecords().forEach(record -> record.setStatus(PrintJobStatus.normalize(record.getStatus())));
        return page;
    }

    private List<String> normalizeQueryStatuses(PrintJobQueryDTO queryDTO) {
        boolean hasStatus = queryDTO.getStatus() != null && !queryDTO.getStatus().isBlank();
        boolean hasStatuses = queryDTO.getStatuses() != null;
        if (hasStatus && hasStatuses) {
            throw new BusinessException(400, "status 与 statuses 不能同时使用");
        }
        if (hasStatuses && queryDTO.getStatuses().isEmpty()) {
            throw new BusinessException(400, "statuses 不能为空");
        }

        List<String> requested = hasStatus
                ? List.of(queryDTO.getStatus())
                : (queryDTO.getStatuses() == null ? List.of() : queryDTO.getStatuses());
        if (requested.size() > 10) {
            throw new BusinessException(400, "任务状态最多选择10项");
        }

        List<String> storageStatuses = new ArrayList<>();
        for (String rawStatus : requested) {
            String status = PrintJobStatus.normalize(rawStatus);
            if (!PrintJobStatus.isSupported(status)) {
                throw new BusinessException(400, "未知任务状态: " + rawStatus);
            }
            if (!storageStatuses.contains(status)) storageStatuses.add(status);
            if (PrintJobStatus.QUEUED.name().equals(status)) {
                storageStatuses.add(PrintJobStatus.LEGACY_PENDING);
                storageStatuses.add(PrintJobStatus.LEGACY_MANUAL);
            } else if (PrintJobStatus.CANCELLED.name().equals(status)) {
                storageStatuses.add(PrintJobStatus.LEGACY_CANCELED);
            }
        }
        return storageStatuses.stream().distinct().toList();
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null) return null;
        String trimmed = fileName.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 255) {
            throw new BusinessException(400, "文件名筛选最多255个字符");
        }
        return trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @Override
    public Page<PrintJob> queryJobsByFileId(Long fileId, FileJobsQueryDTO query) {
        PrintFile file = PrintFileAccess.requireAccessible(fileId, printFileMapper::selectById);
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        boolean admin = SecurityContextUtil.isAdmin();

        int pageNum = query != null && query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query != null && query.getPageSize() != null ? query.getPageSize() : 10;
        return farmPrintJobMapper.selectPageByFileId(
                new Page<>(pageNum, pageSize), fileId, currentUserId, admin);
    }
}
