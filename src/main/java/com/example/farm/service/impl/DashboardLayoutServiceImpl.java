package com.example.farm.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.dto.DashboardAisleDTO;
import com.example.farm.entity.vo.DashboardAisleVO;
import com.example.farm.entity.vo.DashboardLayoutLimitsVO;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.mapper.DashboardLayoutMapper;
import com.example.farm.service.DashboardLayoutService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 动态看板布局读取服务；持久化 JSON 只在此处转成稳定的 API 结构。 */
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
        if (layout.getRowCount() == null || layout.getColumnCount() == null
                || layout.getVersion() == null || layout.getRowCount() < 1
                || layout.getRowCount() > 30 || layout.getColumnCount() < 1
                || layout.getColumnCount() > 60
                || (long) layout.getRowCount() * layout.getColumnCount() > 1800
                || layout.getVersion() < 1) {
            throw invalidLayout();
        }

        List<DashboardAisleDTO> aisles = parseAisles(layout.getAislesJson());
        Set<String> keys = new HashSet<>();
        List<DashboardAisleVO> normalized = aisles.stream().map(aisle -> {
            if (!StringUtils.hasText(aisle.getId()) || aisle.getId().trim().length() > 64
                    || !StringUtils.hasText(aisle.getAxis()) || aisle.getAfterIndex() == null
                    || aisle.getSize() == null || aisle.getSize() < 48 || aisle.getSize() > 240) {
                throw invalidLayout();
            }
            String axis = aisle.getAxis().trim().toUpperCase(Locale.ROOT);
            String label = aisle.getLabel() == null ? null : aisle.getLabel().trim();
            int maxAfterIndex = "ROW".equals(axis) ? layout.getRowCount() : layout.getColumnCount();
            if (!("ROW".equals(axis) || "COLUMN".equals(axis))
                    || aisle.getAfterIndex() < 0 || aisle.getAfterIndex() > maxAfterIndex
                    || !StringUtils.hasText(label) || label.length() > 12) {
                throw invalidLayout();
            }
            String key = axis + ":" + aisle.getAfterIndex();
            if (!keys.add(key)) {
                throw invalidLayout();
            }
            aisle.setId(aisle.getId().trim());
            aisle.setAxis(axis);
            aisle.setLabel(label);
            return DashboardAisleVO.from(aisle);
        }).toList();

        DashboardLayoutVO view = new DashboardLayoutVO();
        view.setRowCount(layout.getRowCount());
        view.setColumnCount(layout.getColumnCount());
        view.setAisles(normalized);
        view.setVersion(layout.getVersion());
        view.setLimits(DashboardLayoutLimitsVO.defaults());
        view.setUpdatedAt(layout.getUpdatedAt());
        return view;
    }

    private List<DashboardAisleDTO> parseAisles(String aislesJson) {
        if (!StringUtils.hasText(aislesJson)) {
            throw invalidLayout();
        }
        try {
            List<DashboardAisleDTO> aisles = objectMapper.readValue(
                    aislesJson, new TypeReference<List<DashboardAisleDTO>>() { });
            if (aisles == null || aisles.size() > 20) {
                throw invalidLayout();
            }
            return aisles;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalidLayout();
        }
    }

    private BusinessException invalidLayout() {
        return new BusinessException("看板布局数据无效");
    }
}
