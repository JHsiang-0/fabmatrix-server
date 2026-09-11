package com.example.farm.service;

import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.vo.DashboardLayoutVO;
import com.example.farm.mapper.DashboardLayoutMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.DashboardLayoutServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DashboardLayoutServiceTest {

    @Test
    void createsDefaultLayoutWhenSingletonIsMissing() {
        DashboardLayoutMapper mapper = mock(DashboardLayoutMapper.class);
        when(mapper.selectById(1L)).thenReturn(null, storedDefault());
        when(mapper.insert(any(DashboardLayout.class))).thenReturn(1);
        DashboardLayoutServiceImpl service = new DashboardLayoutServiceImpl(mapper, new ObjectMapper(), mock(PrinterMapper.class));

        DashboardLayoutVO result = service.getLayout();

        assertThat(result.getRowCount()).isEqualTo(4);
        assertThat(result.getColumnCount()).isEqualTo(12);
        assertThat(result.getVersion()).isEqualTo(1L);
        assertThat(result.getAisles()).singleElement().satisfies(aisle -> {
            assertThat(aisle.getAxis()).isEqualTo("COLUMN");
            assertThat(aisle.getAfterIndex()).isEqualTo(6);
            assertThat(aisle.getSize()).isEqualTo(100);
        });
        assertThat(result.getLimits().getMaxCells()).isEqualTo(1800);
        verify(mapper).insert(any(DashboardLayout.class));
    }

    @Test
    void normalizesPersistedAisleValuesAndRejectsMalformedLayout() {
        DashboardLayoutMapper mapper = mock(DashboardLayoutMapper.class);
        DashboardLayout layout = storedDefault();
        layout.setAislesJson("[{\"id\":\" aisle \",\"axis\":\"column\","
                + "\"afterIndex\":7,\"label\":\" 过道 \",\"size\":100}]");
        when(mapper.selectById(1L)).thenReturn(layout);
        DashboardLayoutServiceImpl service = new DashboardLayoutServiceImpl(mapper, new ObjectMapper(), mock(PrinterMapper.class));

        DashboardLayoutVO result = service.getLayout();

        assertThat(result.getAisles().get(0).getId()).isEqualTo("aisle");
        assertThat(result.getAisles().get(0).getAxis()).isEqualTo("COLUMN");
        assertThat(result.getAisles().get(0).getLabel()).isEqualTo("过道");
    }

    private DashboardLayout storedDefault() {
        DashboardLayout layout = new DashboardLayout();
        layout.setId(1L);
        layout.setRowCount(4);
        layout.setColumnCount(12);
        layout.setAislesJson("[{\"id\":\"default-column-aisle\",\"axis\":\"COLUMN\","
                + "\"afterIndex\":6,\"label\":\"过道\",\"size\":100}]");
        layout.setVersion(1L);
        layout.setUpdatedAt(LocalDateTime.of(2026, 9, 11, 10, 0));
        return layout;
    }
}
