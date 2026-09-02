package com.example.farm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterStatusHistory;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.dto.PrinterHistoryQueryDTO;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.mapper.PrinterStatusHistoryMapper;
import com.example.farm.service.PrinterStatusHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 打印机状态历史服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterStatusHistoryServiceImpl implements PrinterStatusHistoryService {

    private final PrinterStatusHistoryMapper historyMapper;
    private final PrinterMapper printerMapper;

    @Override
    public void record(Long printerId, MoonrakerStatusDTO status) {
        if (printerId == null || status == null) {
            return;
        }
        try {
            PrinterStatusHistory history = new PrinterStatusHistory();
            history.setPrinterId(printerId);
            history.setStatus(status.getUnifiedState());
            history.setRawState(status.getState());
            history.setSystemMessage(status.getSystemMessage());
            history.setFilename(status.getFilename());
            history.setProgress(decimal(status.getProgress()));
            history.setToolTemperature(decimal(status.getToolTemperature()));
            history.setToolTarget(decimal(status.getToolTarget()));
            history.setBedTemperature(decimal(status.getBedTemperature()));
            history.setBedTarget(decimal(status.getBedTarget()));
            history.setPrintDuration(decimal(status.getPrintDuration()));
            history.setTotalDuration(decimal(status.getTotalDuration()));
            history.setFilamentUsed(decimal(status.getFilamentUsed()));
            history.setRecordedAt(LocalDateTime.now());
            historyMapper.insert(history);
        } catch (Exception e) {
            log.error("写入打印机持久化状态历史失败: printerId={}", printerId, e);
        }
    }

    @Override
    public Page<PrinterStatusHistory> page(Long printerId, PrinterHistoryQueryDTO query) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正数");
        }
        Printer printer = printerMapper.selectById(printerId);
        if (printer == null) {
            throw new BusinessException(404, "打印机不存在");
        }
        if (query == null) {
            throw new BusinessException("历史查询参数不能为空");
        }
        if (query.getFrom() != null && query.getTo() != null
                && query.getFrom().isAfter(query.getTo())) {
            throw new BusinessException(400, "开始时间不能晚于结束时间");
        }

        LambdaQueryWrapper<PrinterStatusHistory> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PrinterStatusHistory::getPrinterId, printerId)
                .ge(query.getFrom() != null, PrinterStatusHistory::getRecordedAt, query.getFrom())
                .le(query.getTo() != null, PrinterStatusHistory::getRecordedAt, query.getTo())
                .orderByDesc(PrinterStatusHistory::getRecordedAt)
                .orderByDesc(PrinterStatusHistory::getId);
        return historyMapper.selectPage(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
    }

    private java.math.BigDecimal decimal(Double value) {
        return value == null ? null : java.math.BigDecimal.valueOf(value);
    }
}
