package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterQueryDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.entity.dto.PrinterPositionUpdateDTO;
import com.example.farm.entity.dto.PrinterScanResultDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrinterCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    private static final int MAX_BATCH_SIZE = 100;

    private final PrinterCacheService printerCacheService;
    private final MacAddressUtil macAddressUtil;
    private final PrinterProtocolDetector protocolDetector;

    // ==================== 基础 CRUD 操作 ====================

    @Override
    public Page<Printer> pagePrinters(PrinterQueryDTO queryDTO) {
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
            throw new BusinessException("IP 地址不能为空");
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

            // 设置耗材和喷嘴（使用传入值或默认值）
            newPrinter.setCurrentMaterial(StringUtils.hasText(dto.getCurrentMaterial())
                    ? dto.getCurrentMaterial() : "ABS");
            newPrinter.setNozzleSize(dto.getNozzleSize() != null
                    ? dto.getNozzleSize() : new BigDecimal("0.40"));

            // 设置设备编号和物理位置
            newPrinter.setMachineNumber(dto.getMachineNumber());
            newPrinter.setGridRow(dto.getGridRow());
            newPrinter.setGridCol(dto.getGridCol());

            newPrinter.setCreatedAt(LocalDateTime.now());
            newPrinter.setUpdatedAt(LocalDateTime.now());

            if (!this.save(newPrinter)) {
                throw new BusinessException("新增打印机失败");
            }
            log.info("新增设备成功: ID={}, MAC={}, IP={}, machineNumber={}, gridRow={}, gridCol={}",
                    newPrinter.getId(), macAddress, ipAddress,
                    dto.getMachineNumber(), dto.getGridRow(), dto.getGridCol());
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
        existingPrinter.setApiKey(dto.getApiKey());
        existingPrinter.setCurrentMaterial(dto.getCurrentMaterial());
        existingPrinter.setNozzleSize(dto.getNozzleSize());
        existingPrinter.setMachineNumber(dto.getMachineNumber());

        // 更新物理位置（数字孪生看板用）
        existingPrinter.setGridRow(dto.getGridRow());
        existingPrinter.setGridCol(dto.getGridCol());

        existingPrinter.setUpdatedAt(LocalDateTime.now());

        if (!this.updateById(existingPrinter)) {
            throw new BusinessException("更新打印机失败");
        }
        log.info("更新打印机成功：id={}, name={}, ip={}, gridRow={}, gridCol={}",
                existingPrinter.getId(), existingPrinter.getName(), existingPrinter.getIpAddress(),
                dto.getGridRow(), dto.getGridCol());

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
        log.info("删除打印机成功：id={}, name={}", id, printer.getName());

        printerCacheService.refreshPrinterCache();
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
        log.info("开始扫描局域网 Klipper/RRF 设备：subnet={}", subnet);

        // 获取数据库中所有已存在的 MAC 地址（用于判断新旧设备）
        Set<String> existingMacs = this.list().stream()
                .map(Printer::getMacAddress)
                .filter(StringUtils::hasText)
                .map(mac -> macAddressUtil.normalizeMacAddress(mac))
                .collect(Collectors.toSet());

        ExecutorService executor = Executors.newFixedThreadPool(50);
        List<CompletableFuture<PrinterScanResultDTO>> futures = new ArrayList<>();

        try {
            for (int i = 1; i <= 254; i++) {
                final String targetIp = subnet + "." + i;

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
                        result.setMacAddress(macAddress);
                        result.setFirmwareType(protocolType.name());

                        if (StringUtils.hasText(macAddress)) {
                            String normalizedMac = macAddressUtil.normalizeMacAddress(macAddress);
                            boolean isNew = !existingMacs.contains(normalizedMac);
                            result.setIsNewDevice(isNew);
                            result.setStatus(isNew ? "NEW" : "EXISTING");
                            result.setSuggestedName(macAddressUtil.generateDefaultPrinterName(macAddress));
                        } else {
                            // 无法获取 MAC，标记为需要手动处理
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
                processSingleDevice(result, insertedCount, updatedCount);
                if (Boolean.TRUE.equals(result.getIsNewDevice())) {
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
    private void processSingleDevice(PrinterScanResultDTO result, int insertedCount, int updatedCount) {
        String ipAddress = result.getIpAddress();
        String macAddress = result.getMacAddress();

        // 如果没有 MAC 地址，尝试获取
        if (!StringUtils.hasText(macAddress)) {
            macAddress = macAddressUtil.getMacAddress(ipAddress);
            result.setMacAddress(macAddress);
        } else {
            macAddress = macAddressUtil.normalizeMacAddress(macAddress);
        }

        // 步骤 1: 防 IP 冲突处理
        releaseIpIfOccupied(ipAddress, macAddress);

        // 步骤 2: 基于 MAC 的 Upsert
        if (StringUtils.hasText(macAddress)) {
            // 有 MAC 地址，执行真正的 Upsert
            Printer printer = buildPrinterFromScanResult(result);
            baseMapper.upsertByMacAddress(printer);
        } else {
            // 无 MAC 地址，降级为普通插入
            log.warn("设备无 MAC 地址，降级处理: IP={}", ipAddress);
            Printer printer = buildPrinterFromScanResult(result);
            printer.setMacAddress(null);
            if (!this.save(printer)) {
                throw new BusinessException("新增打印机失败");
            }
        }
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

    // ==================== 物理位置管理 ====================

    /**
     * 【新增】批量更新打印机物理位置坐标（用于数字孪生看板拖拽）
     * <p>接收前端拖拽后的坐标变更，批量更新设备的 grid_row 和 grid_col。</p>
     * <p>业务规则：</p>
     * <ul>
     *     <li>传入 null 表示将该设备移回待分配区</li>
     *     <li>gridRow 范围：1-4</li>
     *     <li>gridCol 范围：1-12</li>
     * </ul>
     *
     * @param positionUpdates 位置更新列表
     * @return 成功更新的设备数量
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchUpdatePositions(List<PrinterPositionUpdateDTO> positionUpdates) {
        if (positionUpdates == null || positionUpdates.isEmpty()) {
            log.warn("批量更新位置跳过：参数为空");
            return 0;
        }

        int successCount = 0;
        int failCount = 0;

        log.info("开始批量更新打印机位置：共 {} 台设备", positionUpdates.size());

        for (PrinterPositionUpdateDTO update : positionUpdates) {
            try {
                // 参数校验
                if (update.getId() == null) {
                    log.warn("跳过无效的位置更新：设备 ID 为空");
                    failCount++;
                    continue;
                }

                // 校验坐标范围（如果有值的话）
                Integer gridRow = update.getGridRow();
                Integer gridCol = update.getGridCol();

                if (gridRow != null && (gridRow < 1 || gridRow > 4)) {
                    log.warn("跳过无效的位置更新：gridRow 超出范围 [1-4]，id={}，gridRow={}",
                            update.getId(), gridRow);
                    failCount++;
                    continue;
                }

                if (gridCol != null && (gridCol < 1 || gridCol > 12)) {
                    log.warn("跳过无效的位置更新：gridCol 超出范围 [1-12]，id={}，gridCol={}",
                            update.getId(), gridCol);
                    failCount++;
                    continue;
                }

                // 检查设备是否存在
                Printer printer = this.getById(update.getId());
                if (printer == null) {
                    log.warn("更新位置失败：打印机不存在，id={}", update.getId());
                    failCount++;
                    continue;
                }

                // 执行更新
                int affected = baseMapper.updatePrinterPosition(update.getId(), gridRow, gridCol);
                if (affected > 0) {
                    successCount++;
                    log.debug("更新设备位置成功：id={}, gridRow={}, gridCol={}",
                            update.getId(), gridRow, gridCol);
                } else {
                    log.warn("更新设备位置失败：id={}", update.getId());
                    failCount++;
                }

            } catch (Exception e) {
                log.error("更新设备位置异常：id={}, 原因={}", update.getId(), e.getMessage());
                failCount++;
            }
        }

        log.info("批量更新位置完成：成功 {} 台，失败 {} 台", successCount, failCount);
        return successCount;
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
