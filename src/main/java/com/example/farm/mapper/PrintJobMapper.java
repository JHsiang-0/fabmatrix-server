package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.vo.PrintJobDetailVO;
import com.example.farm.entity.vo.PrintJobSummaryVO;
import com.example.farm.entity.vo.PrinterStatisticsVO;
import com.example.farm.entity.enums.PrintJobTimeField;
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

    /** 只在任务仍为 QUEUED 且未绑定设备时写入一次派发快照。 */
    int assignJobIfQueued(@Param("jobId") Long jobId,
                          @Param("printerId") Long printerId,
                          @Param("printerName") String printerName,
                          @Param("machineNumber") String machineNumber);

    /** 根任务插入后回写自身 ID；只允许从 NULL 初始化，保证事务内不可漂移。 */
    int initializeRootJobId(@Param("jobId") Long jobId);

    /** 仅当任务仍处于 expectedStatus 时推进状态，避免无锁队列取消覆盖并发写入。 */
    int updateStatusIfCurrent(@Param("jobId") Long jobId,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("targetStatus") String targetStatus,
                              @Param("errorReason") String errorReason);

    /** 锁定重试来源行；MySQL 使用当前读串行化跨实例的同源重试。 */
    Long lockRetrySource(@Param("jobId") Long jobId);

    /** 查询直接来源的重试子任务；MySQL 使用当前读避免 RR 快照看不到并发提交。 */
    PrintJob selectRetryChild(@Param("sourceJobId") Long sourceJobId);

    /** 查询根链尝试冲突；MySQL 使用当前读覆盖并发提交和历史脏链。 */
    PrintJob selectRetryChildByLineage(@Param("rootJobId") Long rootJobId,
                                       @Param("attemptNumber") Integer attemptNumber);

    /** 使用数据库唯一索引安全创建重试子任务；重复请求返回 0。 */
    int insertRetryIfAbsent(PrintJob job);

    /** 查询当前用户可见的 QUEUED 任务摘要，权限条件在 SQL 层固定。 */
    List<PrintJobSummaryVO> selectQueuedSummaries(@Param("userId") Long userId,
                                                  @Param("admin") boolean admin);

    /** 分页查询任务摘要；分页插件固定执行 count + data 两条 SQL。 */
    Page<PrintJobSummaryVO> selectPageSummaries(Page<PrintJobSummaryVO> page,
                                                @Param("statuses") List<String> statuses,
                                                @Param("printerId") Long printerId,
                                                @Param("userId") Long userId,
                                                @Param("currentUserId") Long currentUserId,
                                                @Param("admin") boolean admin,
                                                @Param("fileName") String fileName,
                                                @Param("timeField") PrintJobTimeField timeField,
                                                @Param("startTime") LocalDateTime startTime,
                                                @Param("endTime") LocalDateTime endTime);

    /** TC-002 调用方兼容门面；新查询统一走多状态参数。 */
    default Page<PrintJobSummaryVO> selectPageSummaries(Page<PrintJobSummaryVO> page,
                                                        String status,
                                                        Long printerId,
                                                        Long userId,
                                                        Long currentUserId,
                                                        boolean admin,
                                                        LocalDateTime startTime,
                                                        LocalDateTime endTime) {
        return selectPageSummaries(page,
                status == null || status.isBlank() ? List.of() : List.of(status),
                printerId, userId, currentUserId, admin, null,
                PrintJobTimeField.CREATED_AT, startTime, endTime);
    }

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
