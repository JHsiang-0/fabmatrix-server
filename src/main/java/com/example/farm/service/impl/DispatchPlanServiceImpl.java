package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.farm.common.constant.RedisKeyConstant;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.DispatchPlan;
import com.example.farm.entity.DispatchPlanItem;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrintJobCreateDTO;
import com.example.farm.entity.dto.request.BatchDispatchConfirmRequest;
import com.example.farm.entity.dto.request.BatchDispatchPreviewRequest;
import com.example.farm.entity.enums.DispatchAction;
import com.example.farm.entity.enums.DispatchMode;
import com.example.farm.entity.enums.DispatchPlanItemStatus;
import com.example.farm.entity.enums.DispatchPlanStatus;
import com.example.farm.entity.enums.DispatchStrategy;
import com.example.farm.entity.vo.BatchDispatchConfirmVO;
import com.example.farm.entity.vo.DispatchConflictVO;
import com.example.farm.entity.vo.DispatchPlanItemVO;
import com.example.farm.entity.vo.DispatchPlanPreviewVO;
import com.example.farm.mapper.DispatchPlanItemMapper;
import com.example.farm.mapper.DispatchPlanMapper;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.service.DispatchPlanService;
import com.example.farm.service.PrintJobService;
import com.example.farm.service.PrinterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 批量计划实现。预览阶段只读业务资源并写入计划快照；确认阶段重新读取资源，
 * 通过打印机锁逐项创建任务，避免把前端预览结果当成事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchPlanServiceImpl implements DispatchPlanService {

    private static final int MAX_ITEMS = 100;
    private static final long PLAN_TTL_MINUTES = 15;
    private static final long LOCK_TTL_SECONDS = 30;

    private final DispatchPlanMapper planMapper;
    private final DispatchPlanItemMapper itemMapper;
    private final PrintFileMapper printFileMapper;
    private final PrinterService printerService;
    private final PrintJobService printJobService;
    private final RedisUtil redisUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DispatchPlanPreviewVO preview(BatchDispatchPreviewRequest request) {
        validatePreviewRequest(request);
        Long userId = SecurityContextUtil.getCurrentUserId();
        DispatchStrategy strategy = parseStrategy(request.getStrategy());
        DispatchAction action = parseAction(request.getAction());
        List<Long> fileIds = distinctIds(request.getFileIds(), "文件 ID");
        List<Long> printerIds = distinctIds(request.getPrinterIds(), "打印机 ID");

        Map<Long, PrintFile> files = loadFiles(fileIds);
        Map<Long, Printer> printers = loadPrinters(printerIds);
        DispatchPlan plan = new DispatchPlan();
        plan.setId("dp_" + UUID.randomUUID().toString().replace("-", ""));
        plan.setMode(DispatchMode.USER_BATCH.name());
        plan.setStrategy(strategy.name());
        plan.setAction(action.name());
        plan.setStatus(DispatchPlanStatus.PREVIEWED.name());
        plan.setVersion(1L);
        String confirmationToken = UUID.randomUUID().toString() + UUID.randomUUID();
        plan.setConfirmationTokenHash(sha256(confirmationToken));
        plan.setCreatedBy(userId);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(plan.getCreatedAt());
        plan.setExpiresAt(plan.getCreatedAt().plusMinutes(PLAN_TTL_MINUTES));

        List<DispatchPlanItem> items = buildItems(plan, fileIds, printerIds, files, printers, strategy);
        if (planMapper.insert(planSuccess(plan)) <= 0) {
            throw new BusinessException("保存批量预览计划失败");
        }
        for (DispatchPlanItem item : items) {
            if (itemMapper.insert(item) <= 0) {
                throw new BusinessException("保存批量预览明细失败");
            }
        }

        DispatchPlanPreviewVO response = new DispatchPlanPreviewVO();
        response.setPlanId(plan.getId());
        response.setVersion(plan.getVersion());
        response.setMode(plan.getMode());
        response.setStrategy(plan.getStrategy());
        response.setAction(plan.getAction());
        response.setConfirmationToken(confirmationToken);
        response.setExpiresAt(plan.getExpiresAt());
        for (DispatchPlanItem item : items) {
            response.getItems().add(toItemVO(item, files, printers));
            if (item.getReasonCode() != null) {
                response.getConflicts().add(new DispatchConflictVO(item.getId(), item.getFileId(),
                        item.getPrinterId(), item.getReasonCode(), item.getMessage()));
            }
        }
        return response;
    }

    @Override
    public BatchDispatchConfirmVO confirm(BatchDispatchConfirmRequest request) {
        if (request == null) {
            throw new BusinessException(400, "确认请求不能为空");
        }
        Long userId = SecurityContextUtil.getCurrentUserId();
        DispatchPlan plan = planMapper.selectById(request.getPlanId());
        if (plan == null) {
            throw new BusinessException(404, "批量计划不存在");
        }
        if (!SecurityContextUtil.isAdmin() && !Objects.equals(plan.getCreatedBy(), userId)) {
            throw new BusinessException(404, "批量计划不存在");
        }
        if (!Objects.equals(plan.getVersion(), request.getVersion())) {
            throw new BusinessException(409, "批量计划版本已变化，请重新预览");
        }
        if (!MessageDigest.isEqual(sha256(request.getConfirmationToken()).getBytes(StandardCharsets.UTF_8),
                plan.getConfirmationTokenHash().getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(403, "批量计划确认令牌无效");
        }
        if (plan.getExpiresAt() == null || plan.getExpiresAt().isBefore(LocalDateTime.now())) {
            markExpired(plan);
            throw new BusinessException(409, "批量计划已过期，请重新预览");
        }

        List<DispatchPlanItem> allItems = itemMapper.selectList(Wrappers.<DispatchPlanItem>lambdaQuery()
                .eq(DispatchPlanItem::getPlanId, plan.getId()));
        Map<String, DispatchPlanItem> itemById = new HashMap<>();
        allItems.forEach(item -> itemById.put(item.getId(), item));
        List<String> requestedIds = request.getItemIds() == null ? List.of() : request.getItemIds().stream().distinct().toList();
        if (requestedIds.isEmpty() || requestedIds.size() > MAX_ITEMS
                || requestedIds.stream().anyMatch(id -> !itemById.containsKey(id))) {
            throw new BusinessException(400, "确认的计划明细无效");
        }

        if (!DispatchPlanStatus.PREVIEWED.name().equals(plan.getStatus())) {
            return storedResult(plan, allItems, requestedIds, true);
        }

        plan.setStatus(DispatchPlanStatus.EXECUTING.name());
        plan.setConfirmedAt(LocalDateTime.now());
        plan.setUpdatedAt(plan.getConfirmedAt());
        planMapper.updateById(plan);

        Map<Long, PrintFile> files = loadFiles(allItems.stream().map(DispatchPlanItem::getFileId).toList());
        Map<Long, Printer> printers = loadPrinters(allItems.stream().map(DispatchPlanItem::getPrinterId)
                .filter(Objects::nonNull).distinct().toList());
        for (String itemId : requestedIds) {
            executeItem(plan, itemById.get(itemId), files, printers, userId);
        }

        boolean failed = allItems.stream().filter(item -> requestedIds.contains(item.getId()))
                .anyMatch(item -> DispatchPlanItemStatus.FAILED.name().equals(item.getStatus())
                        || DispatchPlanItemStatus.RETRYABLE.name().equals(item.getStatus()));
        plan.setStatus(failed ? DispatchPlanStatus.PARTIAL_FAILED.name() : DispatchPlanStatus.COMPLETED.name());
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updateById(plan);
        return storedResult(plan, allItems, requestedIds, false);
    }

    private void executeItem(DispatchPlan plan, DispatchPlanItem item,
                             Map<Long, PrintFile> files, Map<Long, Printer> printers, Long userId) {
        if (item == null || !DispatchPlanItemStatus.PENDING.name().equals(item.getStatus())) {
            return;
        }
        PrintFile file = files.get(item.getFileId());
        Printer printer = printers.get(item.getPrinterId());
        if (file == null || Boolean.TRUE.equals(file.getIsFolder())) {
            failItem(item, "FILE_NOT_FOUND", "文件不存在或不是可打印文件", false);
            return;
        }
        if (printer == null) {
            failItem(item, "PRINTER_NOT_FOUND", "打印机不存在", false);
            return;
        }
        Printer latest = printerService.getById(printer.getId());
        if (latest == null || !isAvailable(latest)) {
            failItem(item, "PRINTER_STATE_CHANGED", "打印机已离线、忙碌或已被占用", true);
            return;
        }

        String lockKey = RedisKeyConstant.getKey(RedisKeyConstant.PRINTER_LOCK, latest.getId());
        String lockValue = UUID.randomUUID().toString();
        boolean locked = redisUtil.tryLock(lockKey, lockValue, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!locked) {
            failItem(item, "PRINTER_OCCUPIED", "打印机正在被其他请求操作", true);
            return;
        }
        try {
            Printer checked = printerService.getById(latest.getId());
            if (checked == null || !isAvailable(checked)) {
                failItem(item, "PRINTER_STATE_CHANGED", "确认时打印机状态已变化", true);
                return;
            }
            PrintJobCreateDTO create = new PrintJobCreateDTO();
            create.setFileId(file.getId());
            create.setPrinterId(checked.getId());
            create.setPriority(0);
            Long jobId = printJobService.createJob(create, userId);
            item.setJobId(jobId);
            item.setAttemptCount((item.getAttemptCount() == null ? 0 : item.getAttemptCount()) + 1);
            item.setStartedAt(LocalDateTime.now());
            if (DispatchAction.UPLOAD_ONLY.name().equals(plan.getAction())) {
                item.setStatus(DispatchPlanItemStatus.UPLOADING.name());
                itemMapper.updateById(item);
                printJobService.startPrint(jobId, userId, DispatchAction.UPLOAD_ONLY.name());
                item.setStatus(DispatchPlanItemStatus.READY.name());
                item.setMessage("文件已上传到打印机，等待现场启动");
            } else {
                item.setStatus(DispatchPlanItemStatus.ASSIGNED.name());
                item.setMessage(DispatchAction.START_AFTER_CONFIRM.name().equals(plan.getAction())
                        ? "已派发，需现场安全确认后启动" : "已派发到打印机，未启动");
            }
            item.setReasonCode(null);
            item.setCompletedAt(LocalDateTime.now());
            itemMapper.updateById(item);
        } catch (Exception exception) {
            log.warn("执行批量计划明细失败: planId={}, itemId={}, printerId={}",
                    plan.getId(), item.getId(), item.getPrinterId(), exception);
            failItem(item, "BATCH_EXECUTION_FAILED", "执行失败，请重试该明细", true);
        } finally {
            if (lockValue.equals(redisUtil.getLockValue(lockKey))) {
                redisUtil.unlock(lockKey);
            }
        }
    }

    private List<DispatchPlanItem> buildItems(DispatchPlan plan, List<Long> fileIds, List<Long> printerIds,
                                              Map<Long, PrintFile> files, Map<Long, Printer> printers,
                                              DispatchStrategy strategy) {
        List<Printer> requestedPrinters = printerIds.stream().map(printers::get).toList();
        List<DispatchPlanItem> items = new ArrayList<>();
        Set<Long> usedPrinters = new HashSet<>();
        for (int index = 0; index < fileIds.size(); index++) {
            Long fileId = fileIds.get(index);
            PrintFile file = files.get(fileId);
            Printer printer = selectPrinter(strategy, index, file, requestedPrinters, usedPrinters);
            DispatchPlanItem item = new DispatchPlanItem();
            item.setId("dpi_" + UUID.randomUUID().toString().replace("-", ""));
            item.setPlanId(plan.getId());
            item.setFileId(fileId);
            item.setPrinterId(printer == null ? null : printer.getId());
            item.setStatus(DispatchPlanItemStatus.PENDING.name());
            item.setAttemptCount(0);
            item.setCreatedAt(plan.getCreatedAt());
            item.setUpdatedAt(plan.getCreatedAt());
            String[] conflict = conflict(file, printer, strategy, requestedPrinters.size(), index);
            if (conflict != null) {
                item.setReasonCode(conflict[0]);
                item.setMessage(conflict[1]);
            } else if (printer != null) {
                usedPrinters.add(printer.getId());
            }
            items.add(item);
        }
        return items;
    }

    private Printer selectPrinter(DispatchStrategy strategy, int index, PrintFile file,
                                  List<Printer> printers, Set<Long> usedPrinters) {
        if (strategy == DispatchStrategy.ONE_TO_ONE) {
            return index < printers.size() ? printers.get(index) : null;
        }
        if (strategy == DispatchStrategy.ROUND_ROBIN) {
            return printers.isEmpty() ? null : printers.get(index % printers.size());
        }
        return printers.stream().filter(p -> p != null && !usedPrinters.contains(p.getId())
                && isAvailable(p) && compatible(file, p)).findFirst().orElse(null);
    }

    private String[] conflict(PrintFile file, Printer printer, DispatchStrategy strategy,
                              int printerCount, int index) {
        if (file == null) return new String[]{"FILE_NOT_FOUND", "文件不存在"};
        if (Boolean.TRUE.equals(file.getIsFolder())) return new String[]{"FILE_IS_FOLDER", "不能分配文件夹"};
        if (printer == null) return new String[]{"NO_PRINTER_AVAILABLE", "没有对应的可用打印机"};
        if (strategy == DispatchStrategy.ONE_TO_ONE && index >= printerCount) {
            return new String[]{"COUNT_MISMATCH", "文件和打印机数量不一致"};
        }
        if (!isAvailable(printer)) return new String[]{"PRINTER_UNAVAILABLE", "打印机当前不可用"};
        if (!compatible(file, printer)) return new String[]{"CAPABILITY_MISMATCH", "耗材或喷嘴能力不匹配"};
        return null;
    }

    private boolean compatible(PrintFile file, Printer printer) {
        return (file.getMaterialType() == null || printer.getCurrentMaterial() == null
                || file.getMaterialType().equalsIgnoreCase(printer.getCurrentMaterial()))
                && (file.getNozzleSize() == null || printer.getNozzleSize() == null
                || file.getNozzleSize().compareTo(printer.getNozzleSize()) == 0);
    }

    private boolean isAvailable(Printer printer) {
        return printer != null && "IDLE".equalsIgnoreCase(printer.getStatus())
                && printer.getCurrentJobId() == null;
    }

    private Map<Long, PrintFile> loadFiles(List<Long> ids) {
        Map<Long, PrintFile> result = new HashMap<>();
        if (ids == null) return result;
        printFileMapper.selectBatchIds(ids).forEach(file -> result.put(file.getId(), file));
        return result;
    }

    private Map<Long, Printer> loadPrinters(List<Long> ids) {
        Map<Long, Printer> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) return result;
        List<Printer> printers = printerService.listByIds(ids);
        if (printers != null) printers.forEach(printer -> result.put(printer.getId(), printer));
        return result;
    }

    private DispatchPlanItemVO toItemVO(DispatchPlanItem item, Map<Long, PrintFile> files,
                                        Map<Long, Printer> printers) {
        DispatchPlanItemVO vo = DispatchPlanItemVO.from(item);
        PrintFile file = files.get(item.getFileId());
        Printer printer = printers.get(item.getPrinterId());
        vo.setFileName(file == null ? null : file.getOriginalName());
        vo.setPrinterName(printer == null ? null : printer.getName());
        vo.setPrinterStatus(printer == null ? null : printer.getStatus());
        vo.setCanExecute(DispatchPlanItemStatus.PENDING.name().equals(item.getStatus())
                && item.getReasonCode() == null);
        return vo;
    }

    private BatchDispatchConfirmVO storedResult(DispatchPlan plan, List<DispatchPlanItem> allItems,
                                                List<String> requestedIds, boolean repeated) {
        BatchDispatchConfirmVO result = new BatchDispatchConfirmVO();
        result.setPlanId(plan.getId());
        result.setStatus(plan.getStatus());
        result.setRepeated(repeated);
        Map<Long, PrintFile> files = loadFiles(allItems.stream().filter(i -> requestedIds.contains(i.getId()))
                .map(DispatchPlanItem::getFileId).toList());
        Map<Long, Printer> printers = loadPrinters(allItems.stream().filter(i -> requestedIds.contains(i.getId()))
                .map(DispatchPlanItem::getPrinterId).filter(Objects::nonNull).toList());
        allItems.stream().filter(item -> requestedIds.contains(item.getId()))
                .map(item -> toItemVO(item, files, printers)).forEach(result.getItems()::add);
        return result;
    }

    private void failItem(DispatchPlanItem item, String reasonCode, String message, boolean retryable) {
        item.setStatus(retryable ? DispatchPlanItemStatus.RETRYABLE.name() : DispatchPlanItemStatus.FAILED.name());
        item.setReasonCode(reasonCode);
        item.setMessage(message);
        item.setCompletedAt(LocalDateTime.now());
        itemMapper.updateById(item);
    }

    private void markExpired(DispatchPlan plan) {
        if (DispatchPlanStatus.PREVIEWED.name().equals(plan.getStatus())) {
            plan.setStatus(DispatchPlanStatus.EXPIRED.name());
            planMapper.updateById(plan);
        }
    }

    private void validatePreviewRequest(BatchDispatchPreviewRequest request) {
        if (request == null || request.getFileIds() == null || request.getFileIds().isEmpty()
                || request.getPrinterIds() == null || request.getPrinterIds().isEmpty()) {
            throw new BusinessException(400, "文件和打印机列表不能为空");
        }
        if (request.getFileIds().size() > MAX_ITEMS || request.getPrinterIds().size() > MAX_ITEMS) {
            throw new BusinessException(400, "单次最多处理100个文件或打印机");
        }
    }

    private List<Long> distinctIds(List<Long> ids, String name) {
        List<Long> distinct = ids.stream().distinct().toList();
        if (distinct.size() != ids.size()) throw new BusinessException(400, name + "不能重复");
        return distinct;
    }

    private DispatchStrategy parseStrategy(String value) {
        try { return DispatchStrategy.valueOf(value.trim().toUpperCase()); }
        catch (Exception e) { throw new BusinessException(400, "匹配策略不支持"); }
    }

    private DispatchAction parseAction(String value) {
        try { return DispatchAction.valueOf(value == null ? DispatchAction.UPLOAD_ONLY.name()
                : value.trim().toUpperCase()); }
        catch (Exception e) { throw new BusinessException(400, "执行动作不支持"); }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private DispatchPlan planSuccess(DispatchPlan plan) { return plan; }
}
