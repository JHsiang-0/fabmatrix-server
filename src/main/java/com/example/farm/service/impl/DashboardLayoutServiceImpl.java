package com.example.farm.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.exception.DashboardLayoutProblemException;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.DashboardAisleDTO;
import com.example.farm.entity.dto.DashboardLayoutSaveRequest;
import com.example.farm.entity.dto.DashboardOverflowPolicy;
import com.example.farm.entity.dto.DashboardPositionChangeDTO;
import com.example.farm.entity.vo.DashboardAisleVO;
import com.example.farm.entity.vo.DashboardLayoutLimitsVO;
import com.example.farm.entity.vo.DashboardLayoutProblemVO;
import com.example.farm.entity.vo.DashboardLayoutSaveResultVO;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.entity.vo.DashboardPrinterPositionVO;
import com.example.farm.entity.vo.DashboardPositionChangeVO;
import com.example.farm.mapper.DashboardLayoutMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.AuditLogService;
import com.example.farm.service.DashboardLayoutService;
import com.example.farm.service.WebSocketEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 动态看板布局读取和原子保存服务。 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutServiceImpl extends ServiceImpl<DashboardLayoutMapper, DashboardLayout>
        implements DashboardLayoutService {

    private static final long LAYOUT_ID = 1L;
    private static final String DEFAULT_AISLES_JSON =
            "[{\"id\":\"default-column-aisle\",\"axis\":\"COLUMN\","
                    + "\"afterIndex\":7,\"label\":\"过道\",\"size\":100}]";

    private final DashboardLayoutMapper dashboardLayoutMapper;
    private final ObjectMapper objectMapper;
    private final PrinterMapper printerMapper;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    @Autowired(required = false)
    private WebSocketEventPublisher webSocketEventPublisher;

    @Override
    @Transactional
    public DashboardLayoutVO getLayout() {
        DashboardLayout layout = dashboardLayoutMapper.selectById(LAYOUT_ID);
        if (layout == null) {
            layout = createDefaultLayout();
            if (dashboardLayoutMapper.insert(layout) != 1) {
                throw new BusinessException("默认看板布局创建失败");
            }
            layout = dashboardLayoutMapper.selectById(LAYOUT_ID);
            if (layout == null) {
                throw new BusinessException("默认看板布局读取失败");
            }
        }
        return toView(layout);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DashboardLayoutSaveResultVO saveLayout(DashboardLayoutSaveRequest request) {
        requireAdmin();
        validateRequest(request);

        DashboardLayout layout = dashboardLayoutMapper.selectForUpdate(LAYOUT_ID);
        if (layout == null) {
            getLayout();
            layout = dashboardLayoutMapper.selectForUpdate(LAYOUT_ID);
        }
        if (layout == null) {
            throw new BusinessException("看板布局读取失败");
        }
        if (!request.getExpectedVersion().equals(layout.getVersion())) {
            throw problem(409, "VERSION_CONFLICT", layout.getVersion(), List.of(), List.of());
        }

        List<DashboardAisleDTO> normalizedAisles = normalizeAisles(
                request.getRowCount(), request.getColumnCount(), request.getAisles(), true);
        List<Printer> printers = dashboardLayoutMapper.selectPrintersForUpdate();
        Map<Long, Printer> printersById = new LinkedHashMap<>();
        Map<Long, Coordinate> finalPositions = new LinkedHashMap<>();
        for (Printer printer : printers) {
            printersById.put(printer.getId(), printer);
            validateStoredCoordinate(printer);
            finalPositions.put(printer.getId(), new Coordinate(printer.getGridRow(), printer.getGridCol()));
        }

        Map<Long, DashboardPositionChangeDTO> changes = new LinkedHashMap<>();
        for (DashboardPositionChangeDTO change : request.getPositionChanges()) {
            if (change == null || change.getPrinterId() == null || change.getPrinterId() <= 0) {
                throw problem(400, "INVALID_PRINTER_ID", layout.getVersion(), List.of(), List.of());
            }
            if (changes.put(change.getPrinterId(), change) != null) {
                throw problem(400, "DUPLICATE_PRINTER", layout.getVersion(),
                        List.of(change.getPrinterId()), List.of());
            }
            if (!printersById.containsKey(change.getPrinterId())) {
                throw problem(404, "PRINTER_NOT_FOUND", layout.getVersion(),
                        List.of(change.getPrinterId()), List.of());
            }
            validateRequestedCoordinate(change, request.getRowCount(), request.getColumnCount(), layout.getVersion());
            finalPositions.put(change.getPrinterId(),
                    new Coordinate(change.getGridRow(), change.getGridCol()));
        }

        List<Long> conflicts = findDuplicateSlots(finalPositions);
        if (!conflicts.isEmpty()) {
            throw problem(400, "DUPLICATE_SLOT", layout.getVersion(), conflicts, List.of());
        }

        List<DashboardPrinterPositionVO> outOfBounds = new ArrayList<>();
        for (Printer printer : printers) {
            Coordinate coordinate = finalPositions.get(printer.getId());
            if (coordinate.isAssigned()
                    && (coordinate.row() > request.getRowCount() || coordinate.col() > request.getColumnCount())) {
                outOfBounds.add(toPosition(printer, coordinate));
            }
        }
        if (!outOfBounds.isEmpty() && request.getOverflowPolicy() == DashboardOverflowPolicy.REJECT) {
            throw problem(422, "OUT_OF_BOUNDS", layout.getVersion(), List.of(), outOfBounds);
        }

        List<DashboardPrinterPositionVO> unallocated = new ArrayList<>();
        Set<Long> affectedIds = new LinkedHashSet<>(changes.keySet());
        if (request.getOverflowPolicy() == DashboardOverflowPolicy.UNALLOCATE) {
            for (DashboardPrinterPositionVO printer : outOfBounds) {
                finalPositions.put(printer.getPrinterId(), new Coordinate(null, null));
                unallocated.add(printer);
                affectedIds.add(printer.getPrinterId());
            }
        }

        if (!affectedIds.isEmpty()
                && printerMapper.clearPrinterPositions(affectedIds) != affectedIds.size()) {
            throw new BusinessException("布局坐标清理失败");
        }
        for (Long printerId : affectedIds) {
            Coordinate coordinate = finalPositions.get(printerId);
            if (coordinate != null && coordinate.isAssigned()
                    && printerMapper.updatePrinterPosition(printerId, coordinate.row(), coordinate.col()) != 1) {
                throw new BusinessException("布局坐标保存失败");
            }
        }

        long nextVersion = layout.getVersion() + 1;
        layout.setRowCount(request.getRowCount());
        layout.setColumnCount(request.getColumnCount());
        layout.setAislesJson(serializeAisles(normalizedAisles));
        layout.setVersion(nextVersion);
        layout.setUpdatedBy(SecurityContextUtil.getCurrentUserIdNullable());
        layout.setUpdatedAt(LocalDateTime.now());
        if (dashboardLayoutMapper.updateById(layout) != 1) {
            throw new BusinessException("布局保存失败");
        }

        List<DashboardPositionChangeVO> changed = new ArrayList<>();
        for (Long printerId : affectedIds) {
            Coordinate coordinate = finalPositions.get(printerId);
            changed.add(new DashboardPositionChangeVO(printerId, coordinate.row(), coordinate.col()));
        }
        if (auditLogService != null) {
            auditLogService.recordSuccess("DASHBOARD_LAYOUT_UPDATE", "DASHBOARD_LAYOUT", LAYOUT_ID,
                    "动态看板布局 v" + nextVersion);
        }
        if (webSocketEventPublisher != null) {
            webSocketEventPublisher.publishLayoutUpdated(nextVersion);
        }
        return new DashboardLayoutSaveResultVO(toView(layout), changed, unallocated);
    }

    private void requireAdmin() {
        if (!SecurityContextUtil.isAdmin()) {
            throw new BusinessException(403, "只有管理员可以保存看板布局");
        }
    }

    private void validateRequest(DashboardLayoutSaveRequest request) {
        if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() <= 0
                || request.getRowCount() == null
                || request.getColumnCount() == null || request.getAisles() == null
                || request.getOverflowPolicy() == null || request.getPositionChanges() == null) {
            throw problem(400, "INVALID_REQUEST", null, List.of(), List.of());
        }
        if (request.getRowCount() < 1 || request.getRowCount() > 30
                || request.getColumnCount() < 1 || request.getColumnCount() > 60
                || request.getPositionChanges().size() > 100
                || (long) request.getRowCount() * request.getColumnCount() > 1800) {
            throw problem(400, "CELL_LIMIT_EXCEEDED", request.getExpectedVersion(), List.of(), List.of());
        }
    }

    private void validateRequestedCoordinate(DashboardPositionChangeDTO change,
                                             int rowCount, int columnCount, Long serverVersion) {
        boolean rowMissing = change.getGridRow() == null;
        boolean colMissing = change.getGridCol() == null;
        if (rowMissing != colMissing || (!rowMissing && (change.getGridRow() < 1
                || change.getGridCol() < 1 || change.getGridRow() > rowCount || change.getGridCol() > columnCount))) {
            throw problem(400, "INVALID_COORDINATE", serverVersion, List.of(change.getPrinterId()), List.of());
        }
    }

    private void validateStoredCoordinate(Printer printer) {
        if ((printer.getGridRow() == null) != (printer.getGridCol() == null)
                || (printer.getGridRow() != null && (printer.getGridRow() < 1 || printer.getGridCol() < 1))) {
            throw new BusinessException("打印机坐标数据无效");
        }
    }

    private List<Long> findDuplicateSlots(Map<Long, Coordinate> positions) {
        Map<String, Long> owners = new HashMap<>();
        Set<Long> conflicts = new LinkedHashSet<>();
        for (Map.Entry<Long, Coordinate> entry : positions.entrySet()) {
            if (!entry.getValue().isAssigned()) {
                continue;
            }
            String slot = entry.getValue().row() + ":" + entry.getValue().col();
            Long previous = owners.putIfAbsent(slot, entry.getKey());
            if (previous != null) {
                conflicts.add(previous);
                conflicts.add(entry.getKey());
            }
        }
        return new ArrayList<>(conflicts);
    }

    private DashboardPrinterPositionVO toPosition(Printer printer, Coordinate coordinate) {
        return new DashboardPrinterPositionVO(printer.getId(), printer.getName(), coordinate.row(), coordinate.col());
    }

    private DashboardLayoutProblemException problem(long code, String reason, Long serverVersion,
                                                    List<Long> conflicts,
                                                    List<DashboardPrinterPositionVO> outOfBounds) {
        return new DashboardLayoutProblemException(code, "看板布局保存失败: " + reason,
                new DashboardLayoutProblemVO(reason, serverVersion, conflicts, outOfBounds));
    }

    private DashboardLayout createDefaultLayout() {
        LocalDateTime now = LocalDateTime.now();
        DashboardLayout layout = new DashboardLayout();
        layout.setId(LAYOUT_ID);
        layout.setRowCount(4);
        layout.setColumnCount(12);
        layout.setAislesJson(DEFAULT_AISLES_JSON);
        layout.setVersion(1L);
        layout.setCreatedAt(now);
        layout.setUpdatedAt(now);
        return layout;
    }

    private DashboardLayoutVO toView(DashboardLayout layout) {
        if (layout.getRowCount() == null || layout.getColumnCount() == null || layout.getVersion() == null
                || layout.getRowCount() < 1 || layout.getRowCount() > 30 || layout.getColumnCount() < 1
                || layout.getColumnCount() > 60 || (long) layout.getRowCount() * layout.getColumnCount() > 1800
                || layout.getVersion() < 1) {
            throw invalidLayout();
        }
        List<DashboardAisleDTO> aisles = normalizeAisles(
                layout.getRowCount(), layout.getColumnCount(), parseAisles(layout.getAislesJson()), false);
        DashboardLayoutVO view = new DashboardLayoutVO();
        view.setRowCount(layout.getRowCount());
        view.setColumnCount(layout.getColumnCount());
        view.setAisles(aisles.stream().map(DashboardAisleVO::from).toList());
        view.setVersion(layout.getVersion());
        view.setLimits(DashboardLayoutLimitsVO.defaults());
        view.setUpdatedAt(layout.getUpdatedAt());
        return view;
    }

    private List<DashboardAisleDTO> normalizeAisles(int rowCount, int columnCount,
                                                   List<DashboardAisleDTO> aisles, boolean request) {
        if (aisles == null || aisles.size() > 20) {
            throw invalid(request);
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        List<DashboardAisleDTO> normalized = new ArrayList<>();
        for (DashboardAisleDTO aisle : aisles) {
            if (aisle == null || !StringUtils.hasText(aisle.getId()) || !StringUtils.hasText(aisle.getAxis())
                    || aisle.getAfterIndex() == null || aisle.getSize() == null) {
                throw invalid(request);
            }
            String id = aisle.getId().trim();
            String axis = aisle.getAxis().trim().toUpperCase(Locale.ROOT);
            String label = aisle.getLabel() == null ? null : aisle.getLabel().trim();
            int maxAfterIndex = "ROW".equals(axis) ? rowCount : columnCount;
            if (id.length() > 64 || !ids.add(id) || !("ROW".equals(axis) || "COLUMN".equals(axis))
                    || aisle.getAfterIndex() < 0 || aisle.getAfterIndex() > maxAfterIndex
                    || !StringUtils.hasText(label) || label.length() > 12
                    || aisle.getSize() < 48 || aisle.getSize() > 240
                    || !keys.add(axis + ":" + aisle.getAfterIndex())) {
                throw invalid(request);
            }
            DashboardAisleDTO copy = new DashboardAisleDTO();
            copy.setId(id);
            copy.setAxis(axis);
            copy.setAfterIndex(aisle.getAfterIndex());
            copy.setLabel(label);
            copy.setSize(aisle.getSize());
            normalized.add(copy);
        }
        return normalized;
    }

    private List<DashboardAisleDTO> parseAisles(String aislesJson) {
        if (!StringUtils.hasText(aislesJson)) {
            throw invalidLayout();
        }
        try {
            List<DashboardAisleDTO> aisles = objectMapper.readValue(
                    aislesJson, new TypeReference<List<DashboardAisleDTO>>() { });
            if (aisles == null) {
                throw invalidLayout();
            }
            return aisles;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidLayout();
        }
    }

    private String serializeAisles(List<DashboardAisleDTO> aisles) {
        try {
            return objectMapper.writeValueAsString(aisles);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("布局过道保存失败");
        }
    }

    private RuntimeException invalid(boolean request) {
        return request ? problem(400, "INVALID_AISLE", null, List.of(), List.of()) : invalidLayout();
    }

    private BusinessException invalidLayout() {
        return new BusinessException("看板布局数据无效");
    }

    private record Coordinate(Integer row, Integer col) {
        boolean isAssigned() {
            return row != null && col != null;
        }
    }
}
