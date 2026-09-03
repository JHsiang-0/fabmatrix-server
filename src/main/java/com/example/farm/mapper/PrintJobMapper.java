package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    PrintJob selectByIdempotencyKey(@Param("userId") Long userId,
                                    @Param("idempotencyKey") String idempotencyKey);

    /**
     * 按文件分页查询任务，权限条件在 SQL 层固定。
     */
    Page<PrintJob> selectPageByFileId(Page<PrintJob> page,
                                      @Param("fileId") Long fileId,
                                      @Param("userId") Long userId,
                                      @Param("admin") boolean admin);

    /**
     * 聚合指定打印机的任务统计。
     */
    PrinterStatisticsVO selectPrinterStatistics(@Param("printerId") Long printerId,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

}
