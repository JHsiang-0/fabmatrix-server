package com.example.farm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.farm.entity.Printer;
import com.example.farm.entity.vo.PrinterVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 设备资产与状态表 Mapper 接口
 * </p>
 *
 * @author codexiang
 * @since 2026-03-01
 */
@Mapper
public interface PrinterMapper extends BaseMapper<Printer> {

    /** 监控写入的幂等回查；MySQL 使用锁定读取，避免读到已删除记录的旧事务快照。 */
    Boolean selectMonitorEnabledForUpdate(@Param("id") Long id);

    /** IP 同步前锁定目标、地址占位和等价 MAC 记录；仅 MySQL 使用 FOR UPDATE。 */
    List<Printer> selectAddressSyncPrintersForUpdate(@Param("ids") List<Long> ids,
                                                   @Param("ipAddresses") List<String> ipAddresses,
                                                   @Param("macIdentities") List<String> macIdentities);

    /** 校验原身份和地址后临时释放本批次地址，支持设备互换 IP。 */
    int clearIpAddressIfUnchanged(@Param("expected") Printer expected);

    /** 只写地址和通信待确认标记，避免覆盖任务绑定和现场安全状态。 */
    int updateIpAddressIfUnchanged(@Param("expected") Printer expected,
                                   @Param("ipAddress") String ipAddress);

    /**
     * 根据 MAC 地址查询打印机
     *
     * @param macAddress MAC 地址
     * @return 打印机实体
     */
    Printer selectByMacAddress(@Param("macAddress") String macAddress);

    /**
     * 根据 IP 地址查询打印机
     *
     * @param ipAddress IP 地址
     * @return 打印机实体
     */
    Printer selectByIpAddress(@Param("ipAddress") String ipAddress);

    /**
     * 释放被占用的 IP 地址
     * <p>将占用该 IP 的其他设备的 IP 设为 NULL，状态设为 OFFLINE</p>
     *
     * @param ipAddress 要释放的 IP 地址
     * @param excludeMac 排除的 MAC 地址（当前要使用该 IP 的设备）
     * @return 影响的行数
     */
    int releaseIpAddress(@Param("ipAddress") String ipAddress, @Param("excludeMac") String excludeMac);

    /**
     * 基于 MAC 地址的 Upsert 操作
     * <p>如果 MAC 地址存在则更新，不存在则插入</p>
     *
     * @param printer 打印机实体
     * @return 影响的行数
     */
    int upsertByMacAddress(Printer printer);

    /**
     * 批量 Upsert 操作
     *
     * @param printers 打印机列表
     * @return 影响的行数
     */
    int batchUpsert(@Param("list") List<Printer> printers);

    /**
     * 根据 MAC 地址更新 IP 和状态
     *
     * @param macAddress MAC 地址
     * @param ipAddress 新的 IP 地址
     * @param status 新的状态
     * @return 影响的行数
     */
    int updateIpAndStatusByMac(@Param("macAddress") String macAddress,
                               @Param("ipAddress") String ipAddress,
                               @Param("status") String status);

    /**
     * 统计指定 MAC 地址列表在数据库中的数量
     *
     * @param macList MAC 地址列表
     * @return 存在的数量
     */
    Long countByMacAddresses(@Param("macList") List<String> macList);

    /**
     * 【新增】更新打印机物理位置坐标
     * <p>用于数字孪生看板拖拽后更新设备的 grid_row 和 grid_col</p>
     *
     * @param id 打印机 ID
     * @param gridRow 当前动态布局内的行号，null 表示移回待分配区
     * @param gridCol 当前动态布局内的列号，null 表示移回待分配区
     * @return 影响的行数
     */
    int updatePrinterPosition(@Param("id") Long id,
                              @Param("gridRow") Integer gridRow,
                              @Param("gridCol") Integer gridCol);

    /** 原子布局保存前清空本次变更设备的坐标，支持设备交换。 */
    int clearPrinterPositions(@Param("ids") java.util.Collection<Long> ids);

    /**
     * 清理打印机与指定任务的绑定，并恢复为空闲状态。
     *
     * @param printerId 打印机 ID
     * @param jobId 当前绑定的任务 ID
     * @return 影响的行数
     */
    int clearJobBinding(@Param("printerId") Long printerId, @Param("jobId") Long jobId);

    /** 在通信健康更新时允许显式清空最近错误，避免覆盖其他打印机业务字段。 */
    int updateCommunicationHealth(@Param("printer") Printer printer);

    /** 仅在打印机仍为空闲且未绑定任务时建立占用关系，避免先查后写的竞态。 */
    int bindJobIfIdle(@Param("printerId") Long printerId, @Param("jobId") Long jobId);

    /** 仅当打印机仍绑定指定任务时原子写入现场安全确认。 */
    int confirmSafeIfBound(@Param("printerId") Long printerId, @Param("jobId") Long jobId);

    /**
     * 【新增】查询所有未分配位置的打印机
     * <p>用于数字孪生看板的空槽位绑定下拉列表</p>
     * <p>查询条件：grid_row IS NULL AND grid_col IS NULL</p>
     *
     * @param keyword 可选的搜索关键字（匹配 name 或 machine_number）
     * @return 未分配位置的打印机精简信息列表
     */
    List<PrinterVO> selectUnallocatedPrinters(@Param("keyword") String keyword);
}
