package com.example.farm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.entity.PrinterStatusHistory;
import com.example.farm.entity.dto.MoonrakerStatusDTO;
import com.example.farm.entity.dto.PrinterHistoryQueryDTO;

/**
 * 打印机状态历史服务。
 */
public interface PrinterStatusHistoryService {

    /**
     * 写入一个状态历史样本。写入失败不应中断设备监控主流程。
     */
    void record(Long printerId, MoonrakerStatusDTO status);

    /**
     * 按设备和时间范围分页查询历史样本。
     */
    Page<PrinterStatusHistory> page(Long printerId, PrinterHistoryQueryDTO query);
}
