package com.example.farm.service;

import com.example.farm.entity.dto.PrinterStatisticsQueryDTO;
import com.example.farm.entity.vo.PrinterStatisticsVO;

/**
 * 打印机任务统计服务。
 */
public interface PrinterStatisticsService {

    PrinterStatisticsVO getStatistics(Long printerId, PrinterStatisticsQueryDTO query);
}
