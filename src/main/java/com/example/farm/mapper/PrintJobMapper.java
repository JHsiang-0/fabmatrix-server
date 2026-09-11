package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.entity.vo.PrinterStatisticsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

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

    /** 查询当前用户可见的 QUEUED 任务摘要，权限条件在 SQL 层固定。 */
    List<PrintJobSummaryVO> selectQueuedSummaries(@Param("userId") Long userId,
                                                  @Param("admin") boolean admin);

    /** 分页查询任务摘要；分页插件固定执行 count + data 两条 SQL。 */
    Page<PrintJobSummaryVO> selectPageSummaries(Page<PrintJobSummaryVO> page,
                                                @Param("status") String status,
                                                @Param("printerId") Long printerId,
                                                @Param("userId") Long userId,
                                                @Param("currentUserId") Long currentUserId,
                                                @Param("admin") boolean admin,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /** 查询当前用户可见的单条任务详情摘要，权限条件在 SQL 层固定。 */
    PrintJobDetailVO selectAccessibleDetail(@Param("jobId") Long jobId,
                                             @Param("userId") Long userId,
                                             @Param("admin") boolean admin);

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
