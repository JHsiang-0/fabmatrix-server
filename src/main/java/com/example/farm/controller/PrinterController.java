package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.common.api.PageResult;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.PrinterAddDTO;
import com.example.farm.entity.dto.PrinterQueryDTO;
import com.example.farm.entity.dto.PrinterUpdateDTO;
import com.example.farm.entity.dto.PrinterPositionUpdateDTO;
import com.example.farm.entity.dto.PrinterScanResultDTO;
import com.example.farm.entity.dto.PrinterHistoryQueryDTO;
import com.example.farm.entity.dto.PrinterStatisticsQueryDTO;
import com.example.farm.entity.vo.PrinterVO;
import com.example.farm.entity.vo.PrinterDetailVO;
import com.example.farm.entity.vo.PrinterStatusHistoryVO;
import com.example.farm.entity.vo.PrinterStatisticsVO;
import com.example.farm.service.PrinterService;
import com.example.farm.service.PrinterDetailService;
import com.example.farm.service.PrinterStatusHistoryService;
import com.example.farm.service.PrinterStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 打印机管理接口。
 * <p>提供打印机的分页查询、增删改、局域网扫描与批量录入能力。</p>
 * <p><b>【重构重点】基于 MAC 地址的 Upsert 机制，解决 DHCP 动态分配导致的设备重复录入问题</b></p>
 */
@RestController
@RequestMapping("/api/v1/printers")
@RequiredArgsConstructor
@Tag(name = "打印机管理", description = "打印机资产与状态管理相关接口")
public class PrinterController {

    private final PrinterService printerService;
    private final PrinterDetailService printerDetailService;
    private final PrinterStatusHistoryService printerStatusHistoryService;
    private final PrinterStatisticsService printerStatisticsService;

    /**
     * 分页查询打印机列表。
     *
     * @param queryDTO 查询条件（页码、页大小、名称、状态）
     * @return 打印机分页结果
     */
    @Operation(summary = "分页查询打印机列表", description = "支持按名称和状态筛选的分页查询")
    @GetMapping("/page")
    public Result<PageResult<PrinterVO>> getPrinterPage(@Valid PrinterQueryDTO queryDTO) {
        return Result.success(PageResult.from(printerService.pagePrinters(queryDTO), PrinterVO::from));
    }

    @Operation(summary = "获取打印机详情", description = "返回打印机安全配置、实时状态缓存和当前任务摘要")
    @GetMapping("/{id}")
    public Result<PrinterDetailVO> getPrinterDetail(@PathVariable Long id) {
        return Result.success(printerDetailService.getDetail(id));
    }

    @Operation(summary = "分页查询打印机状态历史", description = "查询持久化状态样本，按记录时间倒序返回")
    @GetMapping("/{id}/history")
    public Result<PageResult<PrinterStatusHistoryVO>> getPrinterHistory(
            @PathVariable Long id, @Valid PrinterHistoryQueryDTO queryDTO) {
        return Result.success(PageResult.from(
                printerStatusHistoryService.page(id, queryDTO), PrinterStatusHistoryVO::from));
    }

    @Operation(summary = "获取打印机任务统计", description = "按任务创建时间统计指定打印机的任务数量和时长")
    @GetMapping("/{id}/statistics")
    public Result<PrinterStatisticsVO> getPrinterStatistics(
            @PathVariable Long id, PrinterStatisticsQueryDTO queryDTO) {
        return Result.success(printerStatisticsService.getStatistics(id, queryDTO));
    }

    /**
     * 【重构核心】新增打印机 - 基于 MAC 地址的 Upsert 机制
     * <p>业务逻辑：</p>
     * <ul>
     *     <li>如果提供了 MAC 地址 → 按 MAC 查询数据库</li>
     *     <li>MAC 存在 → 更新该设备的 IP，状态置为 UNKNOWN，等待下一次协议探测</li>
     *     <li>MAC 不存在 → 检查 IP 是否被占用，如果被占用先释放旧设备，然后插入新记录</li>
     * </ul>
     *
     * @param addDTO 新增参数（ipAddress 必填，macAddress 可选，系统会自动获取）
     * @return 新增/更新结果
     * @throws BusinessException 当 IP 为空或处理失败时抛出
     */
    @Operation(summary = "新增打印机", description = "基于 MAC 地址的 Upsert 机制，解决 DHCP 动态分配导致的设备重复录入问题")
    @PostMapping("/add")
    public Result<String> addPrinter(@Valid @RequestBody PrinterAddDTO addDTO) {
        addDTO = requireBody(addDTO);
        if (!StringUtils.hasText(addDTO.getIpAddress())) {
            throw new BusinessException("打印机 IP 地址不能为空");
        }

        // 调用重构后的 Service 方法（内部实现 Upsert 逻辑）
        printerService.addPrinter(addDTO);

        return Result.success(null, "打印机添加/更新成功");
    }

    /**
     * 更新打印机信息。
     *
     * @param updateDTO 更新参数
     * @return 更新结果
     * @throws BusinessException 当设备 ID 为空或设备不存在时抛出
     */
    @Operation(summary = "更新打印机信息", description = "修改打印机的名称、IP、MAC、耗材等配置信息")
    @PutMapping("/update")
    public Result<String> updatePrinter(@Valid @RequestBody PrinterUpdateDTO updateDTO) {
        updateDTO = requireBody(updateDTO);
        if (updateDTO.getId() == null) {
            throw new BusinessException("设备 ID 不能为空");
        }
        printerService.updatePrinter(updateDTO);
        return Result.success(null, "打印机信息更新成功");
    }

    /**
     * 删除打印机。
     *
     * @param id 打印机 ID
     * @return 删除结果
     * @throws BusinessException 当打印机不存在或处于打印中时抛出
     */
    @Operation(summary = "删除打印机", description = "删除指定的打印机，打印中的设备无法删除")
    @DeleteMapping("/delete/{id}")
    public Result<String> deletePrinter(@PathVariable Long id) {
        printerService.deletePrinter(id);
        return Result.success(null, "打印机删除成功");
    }

    /**
     * 【重构核心】扫描指定网段中的 Klipper/RRF 设备，返回带 MAC 地址的详细信息
     * <p>相比旧版 scan 接口，新版会：</p>
     * <ol>
     *     <li>识别 Moonraker 7125 或 RRF HTTP 80 端口</li>
     *     <li>尝试获取每个设备的 MAC 地址（通过 ARP 表或 Moonraker API）</li>
     *     <li>判断设备是新设备还是已知设备（基于 MAC 地址）</li>
     * </ol>
     *
     * @param subnet 网段前缀，例如 `192.168.1`
     * @return 扫描结果列表（包含 IP、MAC、是否为新设备等）
     * @throws BusinessException 当 subnet 为空时抛出
     */
    @Operation(summary = "扫描局域网打印机", description = "扫描并识别局域网中的 Klipper 或 RRF 打印机")
    @GetMapping("/scan")
    public Result<List<PrinterScanResultDTO>> scanDevices(@RequestParam String subnet) {
        if (!StringUtils.hasText(subnet)) {
            throw new BusinessException(400, "必须提供三段 IPv4 网段前缀，例如 192.168.1");
        }

        List<PrinterScanResultDTO> results = printerService.scanDevices(subnet);

        int newCount = (int) results.stream()
                .filter(PrinterScanResultDTO::getIsNewDevice)
                .count();
        int existingCount = results.size() - newCount;

        String message = String.format("扫描完成，发现 %d 台设备（新设备 %d 台，已知设备 %d 台）",
                results.size(), newCount, existingCount);

        return Result.success(results, message);
    }


    /**
     * 【重构核心】批量新增/更新打印机（基于 MAC 地址的 Upsert 机制）
     * <p>这是解决 DHCP 问题的关键 API：</p>
     * <ul>
     *     <li>接收扫描结果列表（包含 IP 和 MAC）</li>
     *     <li>根据 MAC 地址判断是更新还是插入</li>
     *     <li>MAC 存在 → 更新 IP，状态置为 UNKNOWN，等待下一次协议探测</li>
     *     <li>MAC 不存在 → 插入新记录（真正的新设备）</li>
     * </ul>
     *
     * @param scanResults 扫描结果列表（从 /scan 接口获取）
     * @return 批量操作结果统计
     * @throws BusinessException 当列表为空时抛出
     */
    @Operation(summary = "批量添加打印机", description = "基于 MAC 地址的 Upsert 机制，批量录入扫描到的设备")
    @PostMapping("/batch-add")
    public Result<PrinterService.BatchUpsertResult> batchAdd(
            @Valid @RequestBody @jakarta.validation.constraints.Size(max = 100, message = "单次最多添加100台打印机")
            List<@Valid PrinterScanResultDTO> scanResults) {
        if (scanResults == null || scanResults.isEmpty()) {
            throw new BusinessException(400, "设备列表不能为空");
        }

        PrinterService.BatchUpsertResult result = printerService.batchUpsertPrinters(scanResults);

        return Result.success(result, result.getMessage());
    }


    /**
     * 根据 MAC 地址查询打印机
     *
     * @param macAddress MAC 地址
     * @return 打印机信息
     */
    @Operation(summary = "根据 MAC 地址查询打印机", description = "通过 MAC 地址精确查询打印机信息")
    @GetMapping("/by-mac/{macAddress}")
    public Result<PrinterVO> getByMacAddress(@PathVariable String macAddress) {
        if (!StringUtils.hasText(macAddress)) {
            throw new BusinessException("MAC 地址不能为空");
        }
        Printer printer = printerService.getByMacAddress(macAddress);
        if (printer == null) {
            return Result.success(null, "未找到该 MAC 地址的设备");
        }
        return Result.success(PrinterVO.from(printer));
    }

    /**
     * 根据 IP 地址查询打印机
     *
     * @param ipAddress IP 地址
     * @return 打印机信息
     */
    @Operation(summary = "根据 IP 地址查询打印机", description = "通过 IP 地址精确查询打印机信息")
    @GetMapping("/by-ip/{ipAddress}")
    public Result<PrinterVO> getByIpAddress(@PathVariable String ipAddress) {
        if (!StringUtils.hasText(ipAddress)) {
            throw new BusinessException("IP 地址不能为空");
        }
        Printer printer = printerService.getByIpAddress(ipAddress);
        if (printer == null) {
            return Result.success(null, "未找到该 IP 地址的设备");
        }
        return Result.success(PrinterVO.from(printer));
    }

    /**
     * 【新增】批量更新打印机物理位置坐标（用于数字孪生看板拖拽）
     * <p>接收前端在数字孪生看板上拖拽后产生的坐标变更，批量更新设备的 grid_row 和 grid_col。</p>
     * <p>业务规则：</p>
     * <ul>
     *     <li>gridRow 范围：1-4</li>
     *     <li>gridCol 范围：1-12</li>
     *     <li>传入 null 表示将该设备移回待分配区</li>
     * </ul>
     *
     * @param positionUpdates 位置更新列表（包含 id, gridRow, gridCol）
     * @return 更新结果（成功更新的设备数量）
     * @throws BusinessException 当参数为空时抛出
     */
    @PutMapping("/positions")
    @Operation(summary = "批量更新打印机物理位置", description = "用于数字孪生看板拖拽后更新设备坐标")
    public Result<String> batchUpdatePositions(
            @Valid @RequestBody @jakarta.validation.constraints.Size(max = 100, message = "单次最多更新100台打印机位置")
            List<@Valid PrinterPositionUpdateDTO> positionUpdates) {
        if (positionUpdates == null || positionUpdates.isEmpty()) {
            throw new BusinessException(400, "位置更新列表不能为空");
        }

        int successCount = printerService.batchUpdatePositions(positionUpdates);

        String message = String.format("成功更新 %d/%d 台设备的位置",
                successCount, positionUpdates.size());

        return Result.success(null, message);
    }

    /**
     * 【新增】获取所有未分配位置的打印机列表（用于数字孪生看板空槽位绑定下拉列表）
     * <p>查询条件：grid_row IS NULL AND grid_col IS NULL</p>
     * <p>返回精简字段：id, name, machineNumber, ipAddress, macAddress, status</p>
     *
     * @param keyword 可选的搜索关键字（匹配 name 或 machine_number）
     * @return 未分配位置的打印机列表
     */
    @GetMapping("/unallocated")
    @Operation(summary = "获取未分配位置的打印机列表", description = "用于数字孪生看板的空槽位绑定下拉列表，查询条件：grid_row IS NULL AND grid_col IS NULL")
    public Result<List<PrinterVO>> getUnallocatedPrinters(
            @Parameter(description = "搜索关键字（可选），支持模糊匹配 name 或 machine_number")
            @RequestParam(required = false) String keyword) {

        List<PrinterVO> unallocatedPrinters = printerService.getUnallocatedPrinters(keyword);

        String message = String.format("查询到 %d 台未分配位置的设备", unallocatedPrinters.size());
        return Result.success(unallocatedPrinters, message);
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        return body;
    }
}
