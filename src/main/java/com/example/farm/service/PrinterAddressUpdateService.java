package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.common.utils.RedisUtil;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterProtocolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 将已确认的 MAC/协议发现结果原子地应用到已有资产，不创建或移除设备。 */
@Slf4j
@Service
public class PrinterAddressUpdateService {

    private final PrinterMapper printerMapper;
    private final PrinterCacheService printerCacheService;
    private final TransactionTemplate transactionTemplate;

    @Autowired(required = false)
    private RedisUtil redisUtil;

    public PrinterAddressUpdateService(PrinterMapper printerMapper, PrinterCacheService printerCacheService,
                                       PlatformTransactionManager transactionManager) {
        this.printerMapper = printerMapper;
        this.printerCacheService = printerCacheService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // 必须在设备锁释放前提交，不能加入生命周期未知的外层事务。
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Change(Long printerId, String macAddress, String firmwareType,
                         String oldIpAddress, String newIpAddress) {
    }

    public List<Long> apply(List<Change> changes) {
        List<Change> effective = validateChanges(changes);
        if (effective.isEmpty()) {
            return List.of();
        }
        List<Long> ids = effective.stream().map(Change::printerId).sorted().toList();
        try {
            return withLocks(ids, 0, () -> transactionTemplate.execute(status -> updateInTransaction(effective, ids)));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("设备 IP 地址存在冲突，请重新同步");
        }
    }

    private List<Change> validateChanges(List<Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        List<Change> effective = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        Set<String> macs = new HashSet<>();
        for (Change change : changes) {
            if (change == null || change.printerId() == null || change.printerId() <= 0
                    || normalizeMac(change.macAddress()) == null || normalizeProtocol(change.firmwareType()) == null
                    || change.newIpAddress() == null || change.newIpAddress().isBlank()) {
                throw conflict("设备身份或新 IP 地址不完整，请重新同步");
            }
            if (Objects.equals(change.oldIpAddress(), change.newIpAddress())) {
                continue;
            }
            if (!ids.add(change.printerId()) || !addresses.add(change.newIpAddress())) {
                throw conflict("同步结果包含重复设备或重复 IP 地址");
            }
            if (!macs.add(normalizeMac(change.macAddress()))) {
                throw conflict("设备 MAC 存在重复记录，请人工核对");
            }
            effective.add(change);
        }
        return effective;
    }

    private <T> T withLocks(List<Long> ids, int index, Supplier<T> action) {
        if (index == ids.size()) {
            return action.get();
        }
        Long id = ids.get(index);
        return PrinterLock.execute(id, redisUtil, () -> withLocks(ids, index + 1, action));
    }

    private List<Long> updateInTransaction(List<Change> changes, List<Long> ids) {
        List<String> newAddresses = changes.stream().map(Change::newIpAddress).toList();
        Map<String, Long> targetByMac = new HashMap<>();
        changes.forEach(change -> targetByMac.put(normalizeMac(change.macAddress()), change.printerId()));
        List<String> macIdentities = targetByMac.keySet().stream().map(mac -> mac.replace(":", "")).toList();
        List<Printer> rows = printerMapper.selectAddressSyncPrintersForUpdate(ids, newAddresses, macIdentities);
        Map<Long, Printer> printers = new HashMap<>();
        for (Printer row : rows) {
            printers.put(row.getId(), row);
        }
        for (Change change : changes) {
            Printer current = printers.get(change.printerId());
            if (current == null || !Boolean.TRUE.equals(current.getMonitorEnabled())
                    || !Objects.equals(normalizeMac(current.getMacAddress()), normalizeMac(change.macAddress()))
                    || !Objects.equals(normalizeProtocol(current.getFirmwareType()), normalizeProtocol(change.firmwareType()))
                    || !Objects.equals(current.getIpAddress(), change.oldIpAddress())) {
                throw conflict("打印机配置已变化，请重新同步");
            }
        }
        Set<Long> targetIds = Set.copyOf(ids);
        for (Printer row : rows) {
            Long identityOwner = targetByMac.get(normalizeMac(row.getMacAddress()));
            if (identityOwner != null && !identityOwner.equals(row.getId())) {
                throw conflict("设备 MAC 存在重复记录，请人工核对");
            }
            if (newAddresses.contains(row.getIpAddress()) && !targetIds.contains(row.getId())) {
                throw conflict("新 IP 地址被其他已登记设备占用，请人工核对");
            }
        }
        for (Long id : ids) {
            PrinterLock.ensureValid(id);
            // 原地址已经为空时无需清空；MySQL 对 NULL -> NULL 的写入可能返回 0。
            if (printers.get(id).getIpAddress() != null
                    && printerMapper.clearIpAddressIfUnchanged(printers.get(id)) != 1) {
                throw conflict("打印机配置已变化，IP 同步已回滚");
            }
        }
        Map<Long, Change> byId = new HashMap<>();
        changes.forEach(change -> byId.put(change.printerId(), change));
        for (Long id : ids) {
            PrinterLock.ensureValid(id);
            if (printerMapper.updateIpAddressIfUnchanged(printers.get(id), byId.get(id).newIpAddress()) != 1) {
                throw conflict("打印机配置已变化，IP 同步已回滚");
            }
        }
        ids.forEach(PrinterLock::ensureValid);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateCaches(ids);
            }
        });
        return ids;
    }

    private void invalidateCaches(List<Long> ids) {
        try {
            printerCacheService.refreshPrinterCache();
        } catch (RuntimeException exception) {
            log.warn("设备 IP 已提交，打印机列表缓存刷新失败: printerIds={}", ids);
        }
        for (Long id : ids) {
            try {
                printerCacheService.clearStatusCache(id);
            } catch (RuntimeException exception) {
                log.warn("设备 IP 已提交，状态缓存清理失败: printerId={}", id);
            }
        }
    }

    private static String normalizeMac(String value) {
        return MacAddressUtil.normalizeDeviceMacAddress(value);
    }

    private static PrinterProtocolType normalizeProtocol(String value) {
        try {
            return PrinterProtocolType.normalize(value);
        } catch (BusinessException exception) {
            return null;
        }
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }
}
