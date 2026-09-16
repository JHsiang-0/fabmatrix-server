package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterQueryDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.entity.dto.PrinterScanResultDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.PrinterService;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.PrinterCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 打印机服务实现类
 * <p>核心业务：基于 MAC 地址的 Upsert 机制，解决 DHCP 动态分配导致的设备重复录入问题</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrinterServiceImpl extends ServiceImpl<PrinterMapper, Printer> implements PrinterService {

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private static final int MAX_BATCH_SIZE = 100;

    /**
     * 绑定任务的写入不能绕过打印机列表缓存，否则其他读路径可能继续看到旧绑定。
     * 解除绑定使用带条件的 Mapper 方法，由 clearJobBinding 单独负责刷新。
     */
    @Override
    public boolean updateById(Printer entity) {
        boolean updated = super.updateById(entity);
        if (updated && entity != null) {
            printerCacheService.refreshPrinterCache();
        }
        return updated;
    }

    private final PrinterCacheService printerCacheService;
    private final MacAddressUtil macAddressUtil;
    private final PrinterProtocolDetector protocolDetector;

    // ==================== 基础 CRUD 操作 ====================

    @Override
    public Page<Printer> pagePrinters(PrinterQueryDTO queryDTO) {
        if (queryDTO == null) {
            throw new BusinessException(400, "打印机查询参数不能为空");
        }
        Page<Printer> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<Printer> wrapper = new LambdaQueryWrapper<>();

        wrapper.like(StringUtils.hasText(queryDTO.getName()), Printer::getName, queryDTO.getName());
        wrapper.eq(StringUtils.hasText(queryDTO.getStatus()), Printer::getStatus, queryDTO.getStatus());
        wrapper.orderByDesc(Printer::getCreatedAt);

        return this.page(page, wrapper);
    }

    /**
     * 【重构核心】新增打印机 - 基于 MAC 地址的 Upsert 机制
     * <p>业务逻辑：</p>
     * <ol>
     *     <li>如果提供了 MAC 地址，先按 MAC 查询数据库</li>
     *     <li>MAC 存在 → 更新该设备的 IP 和状态（设备换了 IP 重新上线）</li>
     *     <li>MAC 不存在 → 检查 IP 是否被占用，如果被占用先释放，然后插入新记录</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addPrinter(PrinterAddDTO dto) {
        // 参数校验
        if (!StringUtils.hasText(dto.getIpAddress())) {
            throw new BusinessException(400, "IP 地址不能为空");
        }

        String ipAddress = dto.getIpAddress();
        String macAddress = dto.getMacAddress();

        // 步骤 1: 尝试获取 MAC 地址（如果前端没传）
        if (!StringUtils.hasText(macAddress)) {
            macAddress = macAddressUtil.getMacAddress(ipAddress);
            log.info("自动获取到设备 MAC 地址: IP={}, MAC={}", ipAddress, macAddress);
        } else {
            // 标准化 MAC 地址格式
            macAddress = macAddressUtil.normalizeMacAddress(macAddress);
        }

        // 步骤 2: 如果有 MAC 地址，执行 Upsert 逻辑
        if (StringUtils.hasText(macAddress)) {
            upsertPrinterByMac(dto, macAddress);
        } else {
            // 没有 MAC 地址时的降级处理（兼容旧逻辑）
            fallbackAddWithoutMac(dto);
        }

        // 刷新缓存
        printerCacheService.refreshPrinterCache();
        Printer persisted = getByIpAddress(ipAddress);
        recordSuccess("PRINTER_CREATE", "PRINTER", persisted == null ? null : persisted.getId(),
                persisted == null ? dto.getName() : persisted.getName());
    }

    /**
     * 基于 MAC 地址的 Upsert 核心逻辑
     */
    private void upsertPrinterByMac(PrinterAddDTO dto, String macAddress) {
        String ipAddress = dto.getIpAddress();

        // 查询是否已存在该 MAC 地址的设备
        Printer existingPrinter = baseMapper.selectByMacAddress(macAddress);

        if (existingPrinter != null) {
            // ========== MAC 已存在：更新现有设备 ==========
            log.info("检测到已知设备重新上线: MAC={}, 旧IP={}, 新IP={}",
                    macAddress, existingPrinter.getIpAddress(), ipAddress);

            // 如果 IP 发生变化，需要处理 IP 冲突
            if (!ipAddress.equals(existingPrinter.getIpAddress())) {
                // 检查新 IP 是否被其他设备占用
                releaseIpIfOccupied(ipAddress, macAddress);
            }

            // 更新设备信息
            existingPrinter.setIpAddress(ipAddress);
            // 重新录入只代表设备配置已保存，尚未完成协议状态探测。
            existingPrinter.setStatus(PrinterStatus.UNKNOWN.name());
            existingPrinter.setUpdatedAt(LocalDateTime.now());

            // 可选更新字段
            if (StringUtils.hasText(dto.getName())) {
                existingPrinter.setName(dto.getName());
            }
            existingPrinter.setFirmwareType(normalizeFirmwareType(
                    StringUtils.hasText(dto.getFirmwareType())
                            ? dto.getFirmwareType() : existingPrinter.getFirmwareType()));
            if (StringUtils.hasText(dto.getApiKey())) {
                existingPrinter.setApiKey(dto.getApiKey());
            }

            if (!this.updateById(existingPrinter)) {
                throw new BusinessException("更新已知打印机失败");
            }
            log.info("更新已知设备成功: ID={}, MAC={}, IP={}",
                    existingPrinter.getId(), macAddress, ipAddress);

        } else {
            // ========== MAC 不存在：插入新设备 ==========
            log.info("发现新设备: MAC={}, IP={}", macAddress, ipAddress);

            // 防 IP 冲突处理
            releaseIpIfOccupied(ipAddress, macAddress);

            // 创建新设备
            Printer newPrinter = new Printer();
            newPrinter.setName(StringUtils.hasText(dto.getName())
                    ? dto.getName()
                    : macAddressUtil.generateDefaultPrinterName(macAddress));
            newPrinter.setIpAddress(ipAddress);
            newPrinter.setMacAddress(macAddress);
            newPrinter.setFirmwareType(normalizeFirmwareType(dto.getFirmwareType()));
            newPrinter.setApiKey(dto.getApiKey());
            newPrinter.setStatus(PrinterStatus.UNKNOWN.name());
            newPrinter.setMonitorEnabled(true);
            newPrinter.setConnectionStatus("UNKNOWN");
            newPrinter.setConsecutiveFailureCount(0);

            // 设置耗材和喷嘴（使用传入值或默认值）
            newPrinter.setCurrentMaterial(StringUtils.hasText(dto.getCurrentMaterial())
                    ? dto.getCurrentMaterial() : "ABS");
            newPrinter.setNozzleSize(dto.getNozzleSize() != null
                    ? dto.getNozzleSize() : new BigDecimal("0.40"));

            // 坐标由动态看板布局服务管理；新设备默认进入未分配区。
            newPrinter.setMachineNumber(dto.getMachineNumber());
            newPrinter.setGridRow(null);
            newPrinter.setGridCol(null);

            newPrinter.setCreatedAt(LocalDateTime.now());
            newPrinter.setUpdatedAt(LocalDateTime.now());

            if (!this.save(newPrinter)) {
                throw new BusinessException("新增打印机失败");
            }
            log.info("新增设备成功: ID={}, MAC={}, IP={}, machineNumber={}, unallocated=true",
                    newPrinter.getId(), macAddress, ipAddress, dto.getMachineNumber());
        }
    }

    /**
     * 无 MAC 地址时的降级处理（兼容旧逻辑）
     */
    private void fallbackAddWithoutMac(PrinterAddDTO dto) {
        log.warn("无法获取设备 MAC 地址，使用 IP 作为唯一标识进行添加: IP={}", dto.getIpAddress());

        // 检查 IP 是否已存在
        long count = this.count(new LambdaQueryWrapper<Printer>()
                .eq(Printer::getIpAddress, dto.getIpAddress()));
        if (count > 0) {
            throw new BusinessException("该 IP 地址的打印机已存在！");
        }

        Printer printer = new Printer();
        printer.setName(dto.getName());
        printer.setIpAddress(dto.getIpAddress());
        printer.setFirmwareType(normalizeFirmwareType(dto.getFirmwareType()));
        printer.setApiKey(dto.getApiKey());
        printer.setStatus(PrinterStatus.UNKNOWN.name());
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("UNKNOWN");
        printer.setConsecutiveFailureCount(0);
        printer.setGridRow(null);
        printer.setGridCol(null);
        printer.setCreatedAt(LocalDateTime.now());
        printer.setUpdatedAt(LocalDateTime.now());

        if (!this.save(printer)) {
            throw new BusinessException("新增打印机失败");
        }
        log.info("新增打印机成功（无 MAC）: id={}, ip={}", printer.getId(), printer.getIpAddress());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePrinter(PrinterUpdateDTO dto) {
        Printer existingPrinter = this.getById(dto.getId());
        if (existingPrinter == null) {
            log.warn("更新打印机失败：打印机不存在，id={}", dto.getId());
            throw new BusinessException("该打印机不存在！");
        }

        // 如果修改了 IP，需要处理 IP 冲突
        if (dto.getIpAddress() != null && !dto.getIpAddress().equals(existingPrinter.getIpAddress())) {
            String currentMac = existingPrinter.getMacAddress();
            releaseIpIfOccupied(dto.getIpAddress(), currentMac);
        }

        // 更新基础信息
        existingPrinter.setName(dto.getName());
        existingPrinter.setIpAddress(dto.getIpAddress());
        existingPrinter.setMacAddress(dto.getMacAddress());
        existingPrinter.setFirmwareType(normalizeFirmwareType(
                StringUtils.hasText(dto.getFirmwareType())
                        ? dto.getFirmwareType() : existingPrinter.getFirmwareType()));
        // apiKey 不出现在查询响应中；编辑时未传值表示保留原凭据，避免普通配置修改误清除设备认证。
        if (StringUtils.hasText(dto.getApiKey())) {
            existingPrinter.setApiKey(dto.getApiKey().trim());
        }
        existingPrinter.setCurrentMaterial(dto.getCurrentMaterial());
        existingPrinter.setNozzleSize(dto.getNozzleSize());
        existingPrinter.setMachineNumber(dto.getMachineNumber());

        existingPrinter.setUpdatedAt(LocalDateTime.now());

        if (!this.updateById(existingPrinter)) {
            throw new BusinessException("更新打印机失败");
        }
        log.info("更新打印机成功：id={}, name={}, ip={}，保留原有布局坐标",
                existingPrinter.getId(), existingPrinter.getName(), existingPrinter.getIpAddress());

        recordSuccess("PRINTER_UPDATE", "PRINTER", existingPrinter.getId(), existingPrinter.getName());

        printerCacheService.refreshPrinterCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePrinter(Long id) {
        Printer printer = this.getById(id);
        if (printer == null) {
            log.warn("删除打印机失败：打印机不存在，id={}", id);
            throw new BusinessException("打印机不存在或已被删除！");
        }

        if ("PRINTING".equals(printer.getStatus())) {
            log.warn("删除打印机被阻止：打印机正在打印中，id={}, name={}", id, printer.getName());
            throw new BusinessException("危险操作：该机器正在打印中，无法删除！请先中止打印任务。");
        }

        if (!this.removeById(id)) {
            throw new BusinessException("删除打印机失败");
        }
        recordSuccess("PRINTER_DELETE", "PRINTER", id, printer.getName());
        log.info("删除打印机成功：id={}, name={}", id, printer.getName());

        printerCacheService.refreshPrinterCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean clearJobBinding(Long printerId, Long jobId) {
        if (printerId == null || jobId == null) {
            return false;
        }
        int affected = baseMapper.clearJobBinding(printerId, jobId);
        if (affected > 0) {
            printerCacheService.refreshPrinterCache();
            return true;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean bindJobIfIdle(Long printerId, Long jobId) {
        if (printerId == null || jobId == null) {
            return false;
        }
        int affected = baseMapper.bindJobIfIdle(printerId, jobId);
        if (affected > 0) {
            printerCacheService.refreshPrinterCache();
            return true;
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean confirmSafeIfBound(Long printerId, Long jobId) {
        if (printerId == null || jobId == null) {
            return false;
        }
        int affected = baseMapper.confirmSafeIfBound(printerId, jobId);
        if (affected > 0) {
            printerCacheService.refreshPrinterCache();
            return true;
        }
        Printer current = this.getById(printerId);
        return current != null
                && Objects.equals(current.getCurrentJobId(), jobId)
                && Boolean.TRUE.equals(current.getIsSafeToPrint());
    }

    // ==================== 扫描与批量操作 ====================

    /**
     * 【重构核心】扫描网段内的 Klipper 设备，返回带 MAC 地址的详细信息
     * <p>步骤：</p>
     * <ol>
     *     <li>并发扫描网段内所有 IP 的 7125 端口</li>
     *     <li>对响应的设备尝试获取 MAC 地址（ARP 表或 Moonraker API）</li>
     *     <li>查询数据库判断是新设备还是已知设备</li>
     * </ol>
     */
    @Override
    public List<PrinterScanResultDTO> scanDevices(String subnet) {
        String subnetPrefix = validateSubnetPrefix(subnet);
        log.info("开始扫描局域网 Klipper/RRF 设备：subnet={}", subnetPrefix);

        // MAC 是首选设备标识；部分 RRF 设备或跨网段部署无法获取 MAC，必须按 IP 兜底，
        // 否则已导入设备会一直显示为 UNKNOWN_MAC 并被重复新增。
        Map<String, Printer> existingByMac = new HashMap<>();
        Map<String, Printer> existingByIp = new HashMap<>();
        for (Printer printer : this.list()) {
            if (StringUtils.hasText(printer.getMacAddress())) {
                String normalizedMac = macAddressUtil.normalizeMacAddress(printer.getMacAddress());
                if (StringUtils.hasText(normalizedMac)) {
                    existingByMac.putIfAbsent(normalizedMac, printer);
                }
            }
            if (StringUtils.hasText(printer.getIpAddress())) {
                existingByIp.putIfAbsent(printer.getIpAddress(), printer);
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(50);
        List<CompletableFuture<PrinterScanResultDTO>> futures = new ArrayList<>();

        try {
            for (int i = 1; i <= 254; i++) {
                    final String targetIp = subnetPrefix + "." + i;

                CompletableFuture<PrinterScanResultDTO> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        // 步骤 1: 识别协议，未知协议不加入扫描结果
                        PrinterProtocolType protocolType = protocolDetector.detect(targetIp);
                        if (protocolType == null) {
                            return null;
                        }

                        // 步骤 2: 获取 MAC 地址
                        String macAddress = macAddressUtil.getMacAddress(targetIp);

                        // 步骤 3: 构建扫描结果
                        PrinterScanResultDTO result = new PrinterScanResultDTO();
                        result.setIpAddress(targetIp);
                        String normalizedMac = macAddressUtil.normalizeMacAddress(macAddress);
                        result.setMacAddress(normalizedMac);
                        result.setFirmwareType(protocolType.name());

                        Printer existingPrinter = null;
                        if (StringUtils.hasText(normalizedMac)) {
                            existingPrinter = existingByMac.get(normalizedMac);
                            Printer sameIpPrinter = existingByIp.get(targetIp);
                            if (existingPrinter == null && sameIpPrinter != null
                                    && !StringUtils.hasText(sameIpPrinter.getMacAddress())) {
                                existingPrinter = sameIpPrinter;
                            }
                        } else {
                            existingPrinter = existingByIp.get(targetIp);
                        }

                        if (existingPrinter != null) {
                            result.setIsNewDevice(false);
                            result.setStatus("EXISTING");
                            result.setSuggestedName(StringUtils.hasText(existingPrinter.getName())
                                    ? existingPrinter.getName()
                                    : "Printer_" + targetIp.substring(targetIp.lastIndexOf('.') + 1));
                        } else if (StringUtils.hasText(normalizedMac)) {
                            result.setIsNewDevice(true);
                            result.setStatus("NEW");
                            result.setSuggestedName(macAddressUtil.generateDefaultPrinterName(normalizedMac));
                        } else {
                            // 无 MAC 且 IP 未登记，允许首次录入；后续扫描将通过 IP 识别为已知设备。
                            result.setIsNewDevice(true);
                            result.setStatus("UNKNOWN_MAC");
                            result.setSuggestedName("Printer_" + targetIp.substring(targetIp.lastIndexOf('.') + 1));
                        }

                        return result;

                    } catch (Exception e) {
                        log.debug("扫描设备异常: IP={}, 原因={}", targetIp, e.getMessage());
                        return null;
                    }
                }, executor);

                futures.add(future);
            }

            // 收集扫描结果
            List<PrinterScanResultDTO> results = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            int newCount = (int) results.stream().filter(r -> Boolean.TRUE.equals(r.getIsNewDevice())).count();
            int existingCount = results.size() - newCount;

            log.info("局域网扫描完成：总发现 {} 台设备，其中新设备 {} 台，已知设备 {} 台，subnet={}",
                    results.size(), newCount, existingCount, subnet);

            return results;

        } finally {
            executor.shutdown();
        }
    }

    private String validateSubnetPrefix(String subnet) {
        if (!StringUtils.hasText(subnet)) {
            throw new BusinessException(400, "必须提供三段 IPv4 网段前缀，例如 192.168.1");
        }
        String normalized = subnet.trim();
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 3) {
            throw new BusinessException(400, "网段前缀必须是三段 IPv4 地址，例如 192.168.1");
        }
        for (String octet : octets) {
            try {
                if (octet.isEmpty() || (octet.length() > 1 && octet.startsWith("0"))) {
                    throw new NumberFormatException();
                }
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException exception) {
                throw new BusinessException(400, "网段前缀必须是三段 IPv4 地址，例如 192.168.1");
            }
        }
        return normalized;
    }

    /**
     * 【重构核心】批量新增/更新打印机（基于 MAC 地址的 Upsert 机制）
     * <p>这是解决 DHCP 问题的关键方法：</p>
     * <ul>
     *     <li>每个设备通过 MAC 地址唯一标识</li>
     *     <li>MAC 存在 → 更新 IP 和状态（设备换了 IP 重新上线）</li>
     *     <li>MAC 不存在 → 插入新记录（真正的新设备）</li>
     * </ul>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BatchUpsertResult batchUpsertPrinters(List<PrinterScanResultDTO> scanResults) {
        if (scanResults == null || scanResults.isEmpty()) {
            log.warn("批量 Upsert 跳过：扫描结果为空");
            return new BatchUpsertResult(0, 0, 0, 0);
        }
        if (scanResults.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(400, "单次最多添加" + MAX_BATCH_SIZE + "台打印机");
        }

        int totalCount = scanResults.size();
        int insertedCount = 0;
        int updatedCount = 0;
        int failedCount = 0;

        log.info("开始批量 Upsert 打印机：共 {} 台设备", totalCount);

        // 逐个处理每台设备
        int index = 0;
        BatchUpsertResult batchResult = new BatchUpsertResult(totalCount, 0, 0, 0);
        for (PrinterScanResultDTO result : scanResults) {
            try {
                if (result == null) {
                    throw new BusinessException(400, "设备数据不能为空");
                }
                if (!StringUtils.hasText(result.getIpAddress())) {
                    throw new BusinessException(400, "IP 地址不能为空");
                }
                BatchDeviceOutcome outcome = processSingleDevice(result);
                if (outcome == BatchDeviceOutcome.INSERTED) {
                    insertedCount++;
                } else {
                    updatedCount++;
                }
                batchResult.getItems().add(new PrinterService.BatchUpsertItemResult(
                        index, result.getIpAddress(), result.getMacAddress(), true, "处理成功"));
            } catch (Exception e) {
                String ip = result == null ? null : result.getIpAddress();
                String mac = result == null ? null : result.getMacAddress();
                log.error("处理设备失败: IP={}, MAC={}, 原因={}", ip, mac, e.getMessage());
                failedCount++;
                batchResult.getItems().add(new PrinterService.BatchUpsertItemResult(
                        index, ip, mac, false, e.getMessage() == null ? "处理失败" : e.getMessage()));
            }
            index++;
        }

        // 刷新缓存
        printerCacheService.refreshPrinterCache();

        batchResult.setInsertedCount(insertedCount);
        batchResult.setUpdatedCount(updatedCount);
        batchResult.setFailedCount(failedCount);
        batchResult.setMessage(String.format("批量处理完成：新增 %d 台，更新 %d 台，失败 %d 台",
                insertedCount, updatedCount, failedCount));

        log.info("批量 Upsert 完成: {}", batchResult);
        return batchResult;
    }

    /**
     * 处理单台设备的 Upsert 逻辑
     */
    private BatchDeviceOutcome processSingleDevice(PrinterScanResultDTO result) {
        String ipAddress = result.getIpAddress();
        String macAddress = macAddressUtil.normalizeMacAddress(result.getMacAddress());
        result.setMacAddress(macAddress);

        // 扫描阶段已经尝试过 MAC 探测。批量导入不再逐台重复等待 ARP/API 超时，
        // 无 MAC 设备使用 IP 作为稳定的本地兜底标识。
        if (StringUtils.hasText(macAddress)) {
            Printer existingByMac = baseMapper.selectByMacAddress(macAddress);
            if (existingByMac != null) {
                releaseIpIfOccupied(ipAddress, macAddress);
                updatePrinterFromScanResult(existingByMac, result, macAddress);
                return BatchDeviceOutcome.UPDATED;
            }

            Printer existingByIp = baseMapper.selectByIpAddress(ipAddress);
            if (existingByIp != null && !StringUtils.hasText(existingByIp.getMacAddress())) {
                // 设备首次录入时未取得 MAC，本次扫描取得后直接补全原记录，保留任务和布局关联。
                updatePrinterFromScanResult(existingByIp, result, macAddress);
                return BatchDeviceOutcome.UPDATED;
            }

            releaseIpIfOccupied(ipAddress, macAddress);
            Printer printer = buildPrinterFromScanResult(result);
            baseMapper.upsertByMacAddress(printer);
            return BatchDeviceOutcome.INSERTED;
        } else {
            Printer existingByIp = baseMapper.selectByIpAddress(ipAddress);
            if (existingByIp != null) {
                log.info("设备无 MAC 地址，按 IP 更新已知设备: ID={}, IP={}", existingByIp.getId(), ipAddress);
                updatePrinterFromScanResult(existingByIp, result, null);
                return BatchDeviceOutcome.UPDATED;
            }

            log.warn("设备无 MAC 地址，按 IP 首次录入: IP={}", ipAddress);
            Printer printer = buildPrinterFromScanResult(result);
            printer.setMacAddress(null);
            if (!this.save(printer)) {
                throw new BusinessException("新增打印机失败");
            }
            return BatchDeviceOutcome.INSERTED;
        }
    }

    private void updatePrinterFromScanResult(Printer printer, PrinterScanResultDTO result, String macAddress) {
        printer.setIpAddress(result.getIpAddress());
        if (StringUtils.hasText(macAddress)) {
            printer.setMacAddress(macAddress);
        }
        if (StringUtils.hasText(result.getSuggestedName())) {
            printer.setName(result.getSuggestedName());
        }
        printer.setFirmwareType(normalizeFirmwareType(StringUtils.hasText(result.getFirmwareType())
                ? result.getFirmwareType() : printer.getFirmwareType()));
        if (StringUtils.hasText(result.getApiKey())) {
            printer.setApiKey(result.getApiKey());
        }
        printer.setStatus(PrinterStatus.UNKNOWN.name());
        printer.setUpdatedAt(LocalDateTime.now());
        if (baseMapper.updateById(printer) <= 0) {
            throw new BusinessException("更新已知打印机失败");
        }
    }

    private enum BatchDeviceOutcome {
        INSERTED,
        UPDATED
    }

    /**
     * 从扫描结果构建设备实体
     */
    private Printer buildPrinterFromScanResult(PrinterScanResultDTO result) {
        Printer printer = new Printer();

        // 名称优先使用建议名称，否则自动生成
        String name = StringUtils.hasText(result.getSuggestedName())
                ? result.getSuggestedName()
                : macAddressUtil.generateDefaultPrinterName(result.getMacAddress());
        printer.setName(name);

        printer.setIpAddress(result.getIpAddress());
        printer.setMacAddress(macAddressUtil.normalizeMacAddress(result.getMacAddress()));
        printer.setFirmwareType(normalizeFirmwareType(result.getFirmwareType()));
        printer.setApiKey(result.getApiKey());
        printer.setStatus(PrinterStatus.UNKNOWN.name());
        printer.setMonitorEnabled(true);
        printer.setConnectionStatus("UNKNOWN");
        printer.setConsecutiveFailureCount(0);
        printer.setGridRow(null);
        printer.setGridCol(null);
        printer.setCurrentMaterial("ABS");
        printer.setNozzleSize(new BigDecimal("0.40"));
        printer.setCreatedAt(LocalDateTime.now());
        printer.setUpdatedAt(LocalDateTime.now());

        return printer;
    }

    /**
     * 将协议类型规范化为数据库统一值，同时兼容历史的 Klipper 大小写写法。
     */
    private String normalizeFirmwareType(String firmwareType) {
        return PrinterProtocolType.normalize(StringUtils.hasText(firmwareType)
                ? firmwareType : PrinterProtocolType.KLIPPER.name()).name();
    }


    // ==================== 辅助查询方法 ====================

    @Override
    public Printer getByMacAddress(String macAddress) {
        if (!StringUtils.hasText(macAddress)) {
            return null;
        }
        String normalizedMac = macAddressUtil.normalizeMacAddress(macAddress);
        return baseMapper.selectByMacAddress(normalizedMac);
    }

    @Override
    public Printer getByIpAddress(String ipAddress) {
        if (!StringUtils.hasText(ipAddress)) {
            return null;
        }
        return baseMapper.selectByIpAddress(ipAddress);
    }

    @Override
    public List<Printer> listMonitorPrinters() {
        LambdaQueryWrapper<Printer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Printer::getMonitorEnabled, true)
                .isNotNull(Printer::getIpAddress)
                .ne(Printer::getIpAddress, "")
                .orderByAsc(Printer::getId);
        List<Printer> printers = this.list(wrapper);
        if (printers == null) {
            return List.of();
        }
        return printers.stream().filter(this::isSupportedMonitorProtocol).toList();
    }

    private boolean isSupportedMonitorProtocol(Printer printer) {
        try {
            PrinterProtocolType.normalize(printer.getFirmwareType());
            return true;
        } catch (BusinessException ignored) {
            log.warn("跳过不支持监控协议的打印机: printerId={}, firmwareType={}",
                    printer.getId(), printer.getFirmwareType());
            return false;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMonitorEnabled(Long printerId, boolean enabled) {
        requireAdminForMonitorConfiguration();
        if (printerId == null) {
            throw new BusinessException(400, "打印机 ID 不能为空");
        }
        Printer printer = this.getById(printerId);
        if (printer == null) {
            throw new BusinessException(404, "打印机不存在");
        }
        printer.setMonitorEnabled(enabled);
        if (!this.updateById(printer)) {
            throw new BusinessException("更新打印机监控配置失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdateMonitorEnabled(List<Long> printerIds, boolean enabled) {
        requireAdminForMonitorConfiguration();
        if (printerIds == null || printerIds.isEmpty()) {
            throw new BusinessException(400, "打印机 ID 列表不能为空");
        }
        if (printerIds.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(400, "单次最多更新100台打印机");
        }
        List<Long> distinctIds = printerIds.stream().filter(Objects::nonNull).distinct().toList();
        List<Printer> printers = this.listByIds(distinctIds);
        if (printers.size() != distinctIds.size()) {
            throw new BusinessException(404, "批量更新中包含不存在的打印机");
        }
        printers.forEach(printer -> printer.setMonitorEnabled(enabled));
        this.updateBatchById(printers);
        printerCacheService.refreshPrinterCache();
        return printers.size();
    }

    private void requireAdminForMonitorConfiguration() {
        if (!SecurityContextUtil.isAdmin()) {
            throw new BusinessException(403, "只有管理员可以修改打印机监控配置");
        }
    }

    /**
     * 【核心防冲突逻辑】释放被占用的 IP 地址
     * <p>当新设备要使用某个 IP 时，先将占用该 IP 的旧设备下线（IP 设为 NULL，状态设为 OFFLINE）</p>
     *
     * @param ipAddress 要使用的 IP 地址
     * @param excludeMac 当前要使用该 IP 的设备 MAC（排除自己）
     * @return 是否成功释放了其他设备的 IP
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseIpAddress(String ipAddress, String excludeMac) {
        return releaseIpIfOccupied(ipAddress, excludeMac);
    }

    /**
     * 内部方法：如果 IP 被占用则释放
     */
    private boolean releaseIpIfOccupied(String ipAddress, String excludeMac) {
        // 查询占用该 IP 的其他设备
        Printer occupiedPrinter = baseMapper.selectByIpAddress(ipAddress);

        if (occupiedPrinter != null) {
            String occupiedMac = occupiedPrinter.getMacAddress();

            // 如果是同一台设备（MAC 相同），不需要释放
            if (StringUtils.hasText(occupiedMac) && occupiedMac.equals(excludeMac)) {
                return false;
            }

            // 释放该 IP：将旧设备的 IP 设为 NULL，状态设为 OFFLINE
            log.warn("检测到 IP 冲突，释放旧设备 IP: IP={}, 旧设备ID={}, 旧设备MAC={}",
                    ipAddress, occupiedPrinter.getId(), occupiedMac);

            int affected = baseMapper.releaseIpAddress(ipAddress, excludeMac);

            if (affected > 0) {
                log.info("成功释放 IP: {}, 影响行数: {}", ipAddress, affected);
                return true;
            }
        }

        return false;
    }

    private void recordSuccess(String action, String targetType, Object targetId, String targetLabel) {
        if (auditLogService != null) {
            auditLogService.recordSuccess(action, targetType, targetId, targetLabel);
        }
    }

    // ==================== 未分配位置设备查询 ====================

    /**
     * 【新增】获取所有未分配位置的打印机列表。
     * <p>用于数字孪生看板的空槽位绑定下拉列表。</p>
     * <p>查询条件：grid_row IS NULL AND grid_col IS NULL</p>
     *
     * @param keyword 可选的搜索关键字（匹配 name 或 machine_number）
     * @return 未分配位置的打印机精简信息列表
     */
    @Override
    public List<PrinterVO> getUnallocatedPrinters(String keyword) {
        log.info("查询未分配位置的打印机列表，keyword={}", keyword);

        // 调用 Mapper 查询（支持可选的关键字过滤）
        List<PrinterVO> unallocatedPrinters = baseMapper.selectUnallocatedPrinters(keyword);

        log.info("查询到 {} 台未分配位置的打印机", unallocatedPrinters.size());
        return unallocatedPrinters;
    }
}
