package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.LogUtil;
import com.example.farm.common.utils.RustFsClient;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.entity.dto.request.PrintJobQueryDTO;
import com.example.farm.entity.dto.request.FileJobsQueryDTO;
import com.example.farm.entity.dto.request.UpdatePrintJobPriorityRequest;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterService;
import com.example.farm.service.WebSocketEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PrintJobServiceImpl extends ServiceImpl<PrintJobMapper, PrintJob> implements PrintJobService {

    private final PrintJobMapper farmPrintJobMapper;
    private final PrintFileMapper printFileMapper;
    private final PrinterService printerService;
    private final RustFsClient rustFsClient;
    private final PrinterProtocolAdapterFactory adapterFactory;
    private final WebSocketEventPublisher eventPublisher;

    @Override
    public PrintJobMapper getBaseMapper() {
        return farmPrintJobMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitJob(Long fileId, Long userId, Integer priority) {
        validateUsableFile(fileId, userId);
        PrintJob job = new PrintJob();
        job.setFileId(fileId);
        job.setUserId(userId);
        job.setPriority(priority != null ? priority : 0);
        job.setStatus(PrintJobStatus.QUEUED.name());
        job.setProgress(BigDecimal.ZERO);
        if (!this.save(job)) {
            throw new BusinessException("提交打印任务失败：任务记录保存失败");
        }
        log.info("提交打印任务成功: jobId={}, userId={}, fileId={}, priority={}", job.getId(), userId, fileId, job.getPriority());
        return job.getId();
    }

    @Override
    public List<PrintJob> getQueuedJobs() {
        List<PrintJob> queuedJobs = this.list(new LambdaQueryWrapper<PrintJob>()
                .in(PrintJob::getStatus, PrintJobStatus.queuedStorageValues())
                .orderByDesc(PrintJob::getPriority)
                .orderByAsc(PrintJob::getCreatedAt));
        queuedJobs.forEach(job -> {
            if (!PrintJobStatus.QUEUED.name().equals(job.getStatus())) {
                job.setStatus(PrintJobStatus.QUEUED.name());
                if (this.updateById(job)) {
                    eventPublisher.publishJobStatus(job);
                } else {
                    log.warn("兼容任务状态规范化失败: jobId={}", job.getId());
                }
            }
        });
        return queuedJobs;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignQueuedJob(Long jobId, Long printerId) {
        PrintJob job = this.getById(jobId);
        if (job == null || !PrintJobStatus.QUEUED.name().equals(PrintJobStatus.normalize(job.getStatus()))) {
            return false;
        }

        Printer printer = printerService.getById(printerId);
        if (printer == null || !"IDLE".equals(printer.getStatus())) {
            return false;
        }
        if (printer.getCurrentJobId() != null) {
            return false;
        }

        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.ASSIGNED);
        job.setPrinterId(printerId);
        job.setStatus(PrintJobStatus.ASSIGNED.name());
        if (!this.updateById(job)) {
            throw new BusinessException("自动派发任务失败：任务状态保存失败");
        }

        printer.setStatus("PREPARING");
        printer.setCurrentJobId(job.getId());
        printer.setIsSafeToPrint(false);
        if (!printerService.updateById(printer)) {
            throw new BusinessException("自动派发任务失败：打印机状态保存失败");
        }

        eventPublisher.publishJobStatus(job);
        LogUtil.dataChange("任务自动派发", "FarmPrintJob", job.getId(),
                "已分配到打印机: " + printer.getName());
        return true;
    }

    @Override
    public List<PrintJob> getQueuedJobsForCurrentUser() {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (SecurityContextUtil.isAdmin()) {
            return getQueuedJobs();
        }
        return getQueuedJobs().stream()
                .filter(job -> Objects.equals(job.getUserId(), currentUserId))
                .toList();
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
    @Transactional(rollbackFor = Exception.class)
    public Long createJob(PrintJobCreateDTO req) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        return createJob(req, currentUserId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createJob(PrintJobCreateDTO req, Long userId) {
        if (userId == null) {
            throw new BusinessException("用户未登录，无法创建任务");
        }

        validateUsableFile(req.getFileId(), userId);

        PrintJob job = new PrintJob();
        job.setUserId(userId);
        job.setFileId(req.getFileId());
        job.setPriority(req.getPriority() != null ? req.getPriority() : 0);
        job.setProgress(BigDecimal.ZERO);
        job.setCreatedAt(LocalDateTime.now());

        // 状态：QUEUED（等待派发）
        job.setStatus(PrintJobStatus.QUEUED.name());

        if (!this.save(job)) {
            throw new BusinessException("创建打印任务失败：任务记录保存失败");
        }
        if (req.getPrinterId() != null) {
            assignJob(job.getId(), req.getPrinterId());
        }
        LogUtil.dataChange("创建打印任务", "FarmPrintJob", job.getId(),
                String.format("用户=%d，文件ID=%d", userId, req.getFileId()));
        return job.getId();
    }

    private PrintFile validateUsableFile(Long fileId, Long userId) {
        PrintFile fileRecord = printFileMapper.selectById(fileId);
        if (fileRecord == null) {
            log.warn("创建打印任务失败：切片文件不存在，fileId={}, userId={}", fileId, userId);
            throw new BusinessException(404, "所选的切片文件不存在");
        }
        if (Boolean.TRUE.equals(fileRecord.getIsFolder())) {
            throw new BusinessException(422, "不能对文件夹创建打印任务");
        }
        if (!SecurityContextUtil.isAdmin() && !Objects.equals(fileRecord.getUserId(), userId)) {
            throw new BusinessException(404, "所选的切片文件不存在");
        }
        return fileRecord;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignAndStartPrint(Long jobId, Long printerId) {
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

        // 先进入 ASSIGNED，外部设备调用成功后才进入 PRINTING。
        job.setPrinterId(printerId);
        job.setStatus(PrintJobStatus.ASSIGNED.name());
        updateJobOrThrow(job, "派发打印任务失败");
        printer.setCurrentJobId(jobId);
        printer.setStatus("PREPARING");
        updatePrinterOrThrow(printer, "派发打印任务失败");
        eventPublisher.publishJobStatus(job);

        // 从切片文件获取工艺参数，做材料与喷嘴校验
        PrintFile fileRecord = printFileMapper.selectById(job.getFileId());
        if (fileRecord == null) {
            throw new BusinessException("切片文件数据缺失");
        }

        if (fileRecord.getNozzleSize() != null && printer.getNozzleSize() != null
                && fileRecord.getNozzleSize().compareTo(printer.getNozzleSize()) != 0) {
            throw new BusinessException("喷嘴尺寸不匹配(" + fileRecord.getNozzleSize() + " vs " + printer.getNozzleSize() + ")");
        }
        if (fileRecord.getMaterialType() != null && printer.getCurrentMaterial() != null
                && !fileRecord.getMaterialType().equalsIgnoreCase(printer.getCurrentMaterial())) {
            throw new BusinessException("装载耗材不匹配(" + fileRecord.getMaterialType() + " vs " + printer.getCurrentMaterial() + ")");
        }

        String filename = fileRecord.getOriginalName();
        String safeName = fileRecord.getSafeName();

        LogUtil.bizInfo("任务派发", "任务ID", jobId, "打印机ID", printerId, "文件名", filename);

        org.springframework.core.io.Resource fileStream = rustFsClient.getFileStream(safeName);
        if (fileStream == null) {
            throw new BusinessException("无法从对象存储中读取切片文件");
        }

        adapterFactory.getAdapter(printer.getFirmwareType())
                .uploadFile(endpointOf(printer, PrinterOperation.START_PRINT), fileStream, filename, true);

        PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PRINTING);
        job.setStatus(PrintJobStatus.PRINTING.name());
        job.setStartedAt(LocalDateTime.now());
        updateJobOrThrow(job, "启动打印失败");

        printer.setStatus("PRINTING");
        updatePrinterOrThrow(printer, "启动打印失败");
        eventPublisher.publishJobStatus(job);

        LogUtil.dataChange("启动打印任务", "FarmPrintJob", job.getId(), "已分配到打印机: " + printer.getName());
        return true;
    }

    // =============================================
    // 安全打印流转核心方法实现（现场确认模式）
    // =============================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignJob(Long jobId, Long printerId) {
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

        // 行为：将 Job 的 printerId 设为目标机器，状态改为 ASSIGNED
        job.setPrinterId(printerId);
        job.setStatus(PrintJobStatus.ASSIGNED.name());
        updateJobOrThrow(job, "派发任务失败");

        // 行为：将目标 Printer 的 is_safe_to_print 重置为 false（防范风险）
        printer.setIsSafeToPrint(false);
        printer.setCurrentJobId(jobId);
        updatePrinterOrThrow(printer, "派发任务失败");
        eventPublisher.publishJobStatus(job);

        LogUtil.bizInfo("任务派发（安全模式）", "任务ID", jobId, "打印机ID", printerId, "打印机名称", printer.getName());
        log.info("派发任务成功（已重置安全标记）: jobId={}, printerId={}", jobId, printerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmPrinterSafe(Long printerId, Long operatorId) {
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (!Objects.equals(currentUserId, operatorId)) {
            throw new BusinessException(403, "操作员身份必须来自当前登录用户");
        }
        Printer printer = printerService.getById(printerId);

        if (printer == null) {
            log.warn("确认打印机安全失败：打印机不存在，printerId={}", printerId);
            throw new BusinessException("打印机不存在");
        }

        // 行为：将 Printer 的 is_safe_to_print 设为 true
        printer.setIsSafeToPrint(true);
        updatePrinterOrThrow(printer, "确认打印机安全失败");

        String operatorInfo = operatorId != null ? "operatorId=" + operatorId : "operator=system";
        LogUtil.bizInfo("现场确认安全", "打印机ID", printerId, "打印机名称", printer.getName(), "操作员", operatorInfo);
        log.info("现场确认打印机安全: printerId={}, operatorId={}", printerId, operatorId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startPrint(Long jobId, Long operatorId, String action) {
        if (operatorId == null) {
            throw new BusinessException("启动打印必须记录操作员ID");
        }
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        if (!Objects.equals(currentUserId, operatorId)) {
            throw new BusinessException(403, "操作员身份必须来自当前登录用户");
        }

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

        Long printerId = job.getPrinterId();
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            log.warn("启动打印失败：打印机不存在，printerId={}", printerId);
            throw new BusinessException("打印机不存在");
        }

        // 校验 2：Printer 的 is_safe_to_print 必须为 true（仅在 START_PRINT 时校验）
        if (startPrint && !Boolean.TRUE.equals(printer.getIsSafeToPrint())) {
            log.warn("启动打印失败：热床未确认安全，jobId={}, printerId={}, isSafeToPrint={}",
                    jobId, printerId, printer.getIsSafeToPrint());
            throw new BusinessException("热床未确认安全，禁止打印！请先在现场确认清理完毕后再试");
        }

        // 获取文件信息
        PrintFile fileRecord = printFileMapper.selectById(job.getFileId());
        if (fileRecord == null) {
            throw new BusinessException("切片文件数据缺失");
        }

        String filename = fileRecord.getOriginalName();
        String safeName = fileRecord.getSafeName() != null
                ? fileRecord.getSafeName()
                : filename;

            // 通过协议适配器上传文件
            try {
                org.springframework.core.io.Resource fileStream = rustFsClient.getFileStream(safeName);
                if (fileStream == null) {
                    throw new BusinessException("无法从对象存储中读取切片文件");
                }

                adapterFactory.getAdapter(printer.getFirmwareType())
                        .uploadFile(endpointOf(printer, startPrint
                                ? PrinterOperation.START_PRINT : PrinterOperation.UPLOAD_FILE),
                                fileStream, filename, startPrint);
            } catch (com.example.farm.protocol.PrinterProtocolException exception) {
                throw exception;
        } catch (Exception e) {
            log.error("文件上传失败：设备调用异常，jobId={}, printerId={}, ip={}",
                    jobId, printerId, printer.getIpAddress(), e);
            throw new BusinessException("机器连接失败，请检查网络：" + e.getMessage());
        }

        // 根据 action 决定状态
        if (startPrint) {
            PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.PRINTING);
            // 行为：将 Job 状态改为 PRINTING，记录 operatorId
            job.setStatus(PrintJobStatus.PRINTING.name());
            job.setOperatorId(operatorId);
            job.setStartedAt(LocalDateTime.now());
            updateJobOrThrow(job, "启动打印失败");

            // 行为：将 Printer 的 is_safe_to_print 再次置为 false，状态改为 PRINTING
            printer.setStatus("PRINTING");
            printer.setIsSafeToPrint(false);
            updatePrinterOrThrow(printer, "启动打印失败");
            eventPublisher.publishJobStatus(job);

            LogUtil.bizInfo("现场启动打印", "任务ID", jobId, "打印机ID", printerId, "操作员ID", operatorId, "文件名", filename);
            log.info("现场启动打印成功: jobId={}, printerId={}, operatorId={}", jobId, printerId, operatorId);
        } else {
            // UPLOAD_ONLY: 仅上传文件，状态改为 READY（就绪待机）
            PrintJobStatus.requireTransition(job.getStatus(), PrintJobStatus.READY);
            job.setStatus(PrintJobStatus.READY.name());
            job.setOperatorId(operatorId);
            updateJobOrThrow(job, "上传文件到打印机失败");

            // 打印机状态保持 IDLE（等待手动在机器上点击打印）
            printer.setIsSafeToPrint(false);
            updatePrinterOrThrow(printer, "上传文件到打印机失败");
            eventPublisher.publishJobStatus(job);

            LogUtil.bizInfo("文件上传到机器（待机）", "任务ID", jobId, "打印机ID", printerId, "操作员ID", operatorId, "文件名", filename);
            log.info("文件已上传到机器（待机）: jobId={}, printerId={}, operatorId={}", jobId, printerId, operatorId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        String status = PrintJobStatus.normalize(job.getStatus());
        PrintJobStatus.requireTransition(status, PrintJobStatus.CANCELLED);

        Long printerId = job.getPrinterId();
        if (printerId != null) {
            Printer printer = printerService.getById(printerId);
            if (printer == null) {
                throw new BusinessException(404, "关联打印机不存在");
            }
            if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
                throw new BusinessException(10001, "打印机没有可用的网络地址");
            }
            adapterFactory.getAdapter(printer.getFirmwareType())
                    .cancel(endpointOf(printer, PrinterOperation.CANCEL));
            printer.setCurrentJobId(null);
            printer.setIsSafeToPrint(false);
            updatePrinterOrThrow(printer, "取消打印任务失败");
        }

        job.setStatus(PrintJobStatus.CANCELLED.name());
        updateJobAndPublish(job);
        log.info("取消打印任务成功: jobId={}, 原状态={}", jobId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        String status = PrintJobStatus.normalize(job.getStatus());
        PrintJobStatus.requireTransition(status, PrintJobStatus.QUEUED);

        job.setPrinterId(null);
        job.setOperatorId(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setErrorReason(null);
        job.setProgress(BigDecimal.ZERO);
        job.setStatus(PrintJobStatus.QUEUED.name());
        updateJobAndPublish(job);
        log.info("重试打印任务成功: jobId={}, 原状态={}", jobId, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requeueJob(Long jobId) {
        PrintJob job = getAccessibleJob(jobId);
        String status = PrintJobStatus.normalize(job.getStatus());
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
                updatePrinterOrThrow(printer, "重新排队任务失败");
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
        log.info("重新排队打印任务成功: jobId={}, 原状态={}", jobId, status);
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

    private PrinterEndpoint endpointOf(Printer printer, PrinterOperation operation) {
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(printer.getFirmwareType());
        if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
            throw new BusinessException(10001, "打印机没有可用的网络地址");
        }
        return new PrinterEndpoint(printer.getId(), printer.getIpAddress(), printer.getApiKey(), protocolType);
    }

    @Override
    public Page<PrintJob> queryJobs(PrintJobQueryDTO queryDTO) {
        if (queryDTO == null) {
            throw new BusinessException(400, "任务查询参数不能为空");
        }
        if (queryDTO.getStartTime() != null && queryDTO.getEndTime() != null
                && queryDTO.getStartTime().isAfter(queryDTO.getEndTime())) {
            throw new BusinessException(400, "开始时间不能晚于结束时间");
        }
        int pageNum = queryDTO.getPageNum() != null ? queryDTO.getPageNum() : 1;
        int pageSize = queryDTO.getPageSize() != null ? queryDTO.getPageSize() : 10;

        LambdaQueryWrapper<PrintJob> wrapper = new LambdaQueryWrapper<>();

        // 状态精确匹配
        String queryStatus = PrintJobStatus.normalize(queryDTO.getStatus());
        wrapper.eq(queryStatus != null && !queryStatus.isEmpty(),
                PrintJob::getStatus, queryStatus);

        // 打印机ID精确匹配
        wrapper.eq(queryDTO.getPrinterId() != null,
                PrintJob::getPrinterId, queryDTO.getPrinterId());

        // 用户ID精确匹配
        if (SecurityContextUtil.isAdmin()) {
            wrapper.eq(queryDTO.getUserId() != null,
                    PrintJob::getUserId, queryDTO.getUserId());
        } else {
            wrapper.eq(PrintJob::getUserId, SecurityContextUtil.getCurrentUserId());
        }

        // 创建时间范围查询
        wrapper.ge(queryDTO.getStartTime() != null,
                PrintJob::getCreatedAt, queryDTO.getStartTime());
        wrapper.le(queryDTO.getEndTime() != null,
                PrintJob::getCreatedAt, queryDTO.getEndTime());

        // 按创建时间降序排列
        wrapper.orderByDesc(PrintJob::getCreatedAt);

        return this.page(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public Page<PrintJob> queryJobsByFileId(Long fileId, FileJobsQueryDTO query) {
        PrintFile file = printFileMapper.selectById(fileId);
        Long currentUserId = SecurityContextUtil.getCurrentUserId();
        boolean admin = SecurityContextUtil.isAdmin();
        if (file == null || (!admin && !Objects.equals(file.getUserId(), currentUserId))) {
            throw new BusinessException(404, "文件不存在");
        }

        int pageNum = query != null && query.getPageNum() != null ? query.getPageNum() : 1;
        int pageSize = query != null && query.getPageSize() != null ? query.getPageSize() : 10;
        return farmPrintJobMapper.selectPageByFileId(
                new Page<>(pageNum, pageSize), fileId, currentUserId, admin);
    }
}
