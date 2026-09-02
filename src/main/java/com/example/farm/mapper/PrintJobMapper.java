package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.vo.PrinterStatisticsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * <p>
 * 打印任务 Mapper 接口
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Mapper
public interface PrintJobMapper extends BaseMapper<PrintJob> {

    /**
     * 聚合指定打印机的任务统计。
     */
    PrinterStatisticsVO selectPrinterStatistics(@Param("printerId") Long printerId,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

}
