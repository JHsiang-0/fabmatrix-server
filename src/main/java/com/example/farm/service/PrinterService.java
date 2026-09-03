package com.example.farm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterQueryDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.entity.dto.PrinterPositionUpdateDTO;
import com.example.farm.entity.dto.PrinterScanResultDTO;
import com.example.farm.entity.vo.PrinterVO;

import java.util.List;

/**
 * 打印机服务接口。
 */
public interface PrinterService extends IService<Printer> {

    /**
     * 分页查询打印机列表。
     *
     * @param queryDTO 查询参数
     * @return 打印机分页结果
     */
    Page<Printer> pagePrinters(PrinterQueryDTO queryDTO);

    /**
     * 新增打印机（基于 MAC 地址的 Upsert 机制）。
     * <p>如果 MAC 地址已存在，则更新该设备的 IP 和状态；</p>
     * <p>如果 MAC 地址不存在，则插入新设备。</p>
     *
     * @param addDTO 新增参数（必须包含 ipAddress）
     * @throws BusinessException 当参数非法或 IP 被其他设备占用时抛出
     */
    void addPrinter(PrinterAddDTO addDTO);

    /**
     * 更新打印机信息。
     *
     * @param updateDTO 更新参数
     * @throws BusinessException 当打印机不存在或目标 IP 被占用时抛出
     */
    void updatePrinter(PrinterUpdateDTO updateDTO);

    /**
     * 删除打印机。
     *
     * @param id 打印机 ID
     * @throws BusinessException 当打印机不存在或正在打印时抛出
     */
    void deletePrinter(Long id);

    /**
     * 清理打印机与指定任务的绑定，并恢复为空闲状态。
     *
     * @param printerId 打印机 ID
     * @param jobId 当前绑定的任务 ID
     * @return 是否成功清理
     */
    boolean clearJobBinding(Long printerId, Long jobId);

    /** 以数据库条件更新原子绑定空闲打印机。 */
    boolean bindJobIfIdle(Long printerId, Long jobId);

    /**
     * 【重构】扫描网段内的 Klipper/RRF 设备，返回带 MAC 地址的详细信息。
     * <p>扫描过程中会识别协议并尝试获取每个设备的 MAC 地址，用于后续 Upsert 操作。</p>
     *
     * @param subnet 网段前缀，例如 `192.168.1`
     * @return 扫描到的设备列表（包含 IP、MAC、是否为新设备等）
     */
    List<PrinterScanResultDTO> scanDevices(String subnet);

    /**
     * 历史方法名兼容入口；扫描结果现在同时支持 Klipper 和 RRF。
     */
    @Deprecated
    default List<PrinterScanResultDTO> scanKlipperDevices(String subnet) {
        return scanDevices(subnet);
    }


    /**
     * 【重构】批量新增/更新打印机（基于 MAC 地址的 Upsert 机制）。
     * <p>根据 MAC 地址判断是更新还是插入：</p>
     * <ul>
     *     <li>MAC 存在 → 更新 IP，状态置为 UNKNOWN，等待下一次协议探测</li>
     *     <li>MAC 不存在 → 插入状态为 UNKNOWN 的新记录</li>
     * </ul>
     *
     * @param scanResults 扫描结果列表（必须包含 macAddress）
     * @return 处理结果统计（成功数、更新数、插入数等）
     * @throws BusinessException 当入参为空或处理失败时抛出
     */
    BatchUpsertResult batchUpsertPrinters(List<PrinterScanResultDTO> scanResults);


    /**
     * 根据 MAC 地址查询打印机。
     *
     * @param macAddress MAC 地址
     * @return 打印机实体，不存在返回 null
     */
    Printer getByMacAddress(String macAddress);

    /**
     * 根据 IP 地址查询打印机。
     *
     * @param ipAddress IP 地址
     * @return 打印机实体，不存在返回 null
     */
    Printer getByIpAddress(String ipAddress);

    /**
     * 释放被占用的 IP 地址（将对应设备的 IP 设为 NULL，状态设为 OFFLINE）。
     * <p>用于处理 IP 冲突：当新设备要使用某个 IP 时，先将占用该 IP 的旧设备下线。</p>
     *
     * @param ipAddress 要释放的 IP 地址
     * @param excludeMac 排除的 MAC 地址（当前要使用该 IP 的设备 MAC）
     * @return 是否成功释放了其他设备的 IP
     */
    boolean releaseIpAddress(String ipAddress, String excludeMac);

    /**
     * 【新增】批量更新打印机物理位置坐标（用于数字孪生看板拖拽）。
     * <p>接收前端拖拽后的坐标变更，批量更新设备的 grid_row 和 grid_col。</p>
     * <p>如果传入 null，表示将该设备移回待分配区。</p>
     *
     * @param positionUpdates 位置更新列表（包含 id, gridRow, gridCol）
     * @return 成功更新的设备数量
     * @throws BusinessException 当参数非法或设备不存在时抛出
     */
    int batchUpdatePositions(List<PrinterPositionUpdateDTO> positionUpdates);

    /**
     * 【新增】获取所有未分配位置的打印机列表。
     * <p>用于数字孪生看板的空槽位绑定下拉列表。</p>
     * <p>查询条件：grid_row IS NULL AND grid_col IS NULL</p>
     *
     * @param keyword 可选的搜索关键字（匹配 name 或 machine_number）
     * @return 未分配位置的打印机精简信息列表
     */
    List<PrinterVO> getUnallocatedPrinters(String keyword);

    /**
     * 批量操作结果封装类
     */
    class BatchUpsertResult {
        private int totalCount;      // 总处理数
        private int insertedCount;   // 新增数
        private int updatedCount;    // 更新数
        private int failedCount;     // 失败数
        private String message;      // 结果消息
        private List<BatchUpsertItemResult> items = new java.util.ArrayList<>();

        public BatchUpsertResult() {}

        public BatchUpsertResult(int total, int inserted, int updated, int failed) {
            this.totalCount = total;
            this.insertedCount = inserted;
            this.updatedCount = updated;
            this.failedCount = failed;
        }

        // Getters and Setters
        public int getTotalCount() { return totalCount; }
        public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
        public int getInsertedCount() { return insertedCount; }
        public void setInsertedCount(int insertedCount) { this.insertedCount = insertedCount; }
        public int getUpdatedCount() { return updatedCount; }
        public void setUpdatedCount(int updatedCount) { this.updatedCount = updatedCount; }
        public int getFailedCount() { return failedCount; }
        public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public List<BatchUpsertItemResult> getItems() { return items; }
        public void setItems(List<BatchUpsertItemResult> items) { this.items = items; }

        @Override
        public String toString() {
            return String.format("BatchUpsertResult{total=%d, inserted=%d, updated=%d, failed=%d}",
                    totalCount, insertedCount, updatedCount, failedCount);
        }
    }

    /**
     * 批量录入单台打印机的处理结果。
     */
    class BatchUpsertItemResult {
        private int index;
        private String ipAddress;
        private String macAddress;
        private boolean success;
        private String reason;

        public BatchUpsertItemResult() {}

        public BatchUpsertItemResult(int index, String ipAddress, String macAddress,
                                     boolean success, String reason) {
            this.index = index;
            this.ipAddress = ipAddress;
            this.macAddress = macAddress;
            this.success = success;
            this.reason = reason;
        }

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
        public String getMacAddress() { return macAddress; }
        public void setMacAddress(String macAddress) { this.macAddress = macAddress; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
