package com.example.farm.service;

import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.DashboardAisleDTO;
import com.example.farm.entity.dto.DashboardLayoutSaveRequest;
import com.example.farm.entity.dto.DashboardOverflowPolicy;
import com.example.farm.entity.dto.DashboardPositionChangeDTO;
import com.example.farm.entity.vo.DashboardLayoutSaveResultVO;
import com.example.farm.mapper.DashboardLayoutMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.service.impl.DashboardLayoutServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DashboardLayoutSaveServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void swapsCoordinatesByClearingBeforeWritingAndIncrementsVersion() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        DashboardLayout layout = layout(1L, 4, 12);
        Printer first = printer(11L, "first", 1, 1);
        Printer second = printer(12L, "second", 1, 2);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout);
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of(first, second));
        when(printerMapper.clearPrinterPositions(any())).thenReturn(2);
        when(printerMapper.updatePrinterPosition(any(), any(), any())).thenReturn(1);
        when(layoutMapper.updateById(any(DashboardLayout.class))).thenReturn(1);
        admin();

        DashboardLayoutSaveResultVO result = service(layoutMapper, printerMapper).saveLayout(
                request(1L, 4, 12, DashboardOverflowPolicy.REJECT,
                        change(11L, 1, 2), change(12L, 1, 1)));

        assertThat(result.getLayout().getVersion()).isEqualTo(2L);
        verify(printerMapper).clearPrinterPositions(any());
        verify(printerMapper).updatePrinterPosition(11L, 1, 2);
        verify(printerMapper).updatePrinterPosition(12L, 1, 1);
        verify(layoutMapper).updateById(layout);
        assertThat(layout.getAislesJson()).isEqualTo("[]");
    }

    @Test
    void rejectPolicyReturnsFullOutOfBoundsListWithoutWrites() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(1L, 4, 12));
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of(printer(11L, "first", 4, 12)));
        admin();

        assertThatThrownBy(() -> service(layoutMapper, printerMapper).saveLayout(
                request(1L, 3, 12, DashboardOverflowPolicy.REJECT)))
                .hasMessageContaining("OUT_OF_BOUNDS")
                .extracting("problem.outOfBoundsPrinters")
                .asList().hasSize(1);
        verifyNoInteractions(printerMapper);
        verify(layoutMapper, never()).updateById(any(DashboardLayout.class));
    }

    @Test
    void unallocatePolicyClearsOutOfBoundsDeviceInSameSave() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(1L, 4, 12));
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of(printer(11L, "first", 4, 12)));
        when(printerMapper.clearPrinterPositions(any())).thenReturn(1);
        when(layoutMapper.updateById(any(DashboardLayout.class))).thenReturn(1);
        admin();

        DashboardLayoutSaveResultVO result = service(layoutMapper, printerMapper).saveLayout(
                request(1L, 3, 12, DashboardOverflowPolicy.UNALLOCATE));

        assertThat(result.getUnallocatedPrinters()).singleElement()
                .extracting("printerId").isEqualTo(11L);
        verify(printerMapper).clearPrinterPositions(any());
        verify(printerMapper, never()).updatePrinterPosition(any(), any(), any());
    }

    @Test
    void staleVersionIsRejectedBeforeCoordinateWrites() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(3L, 4, 12));
        admin();

        assertThatThrownBy(() -> service(layoutMapper, printerMapper).saveLayout(
                request(1L, 4, 12, DashboardOverflowPolicy.REJECT)))
                .hasMessageContaining("VERSION_CONFLICT")
                .extracting("problem.serverVersion").isEqualTo(3L);
        verifyNoInteractions(printerMapper);
    }

    private DashboardLayoutServiceImpl service(DashboardLayoutMapper mapper, PrinterMapper printerMapper) {
        return new DashboardLayoutServiceImpl(mapper, new ObjectMapper(), printerMapper);
    }

    private void admin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private DashboardLayout layout(long version, int rows, int columns) {
        DashboardLayout layout = new DashboardLayout();
        layout.setId(1L);
        layout.setVersion(version);
        layout.setRowCount(rows);
        layout.setColumnCount(columns);
        layout.setAislesJson("[]");
        return layout;
    }

    private Printer printer(long id, String name, int row, int column) {
        Printer printer = new Printer();
        printer.setId(id);
        printer.setName(name);
        printer.setGridRow(row);
        printer.setGridCol(column);
        return printer;
    }

    private DashboardLayoutSaveRequest request(long version, int rows, int columns,
                                               DashboardOverflowPolicy policy,
                                               DashboardPositionChangeDTO... changes) {
        DashboardLayoutSaveRequest request = new DashboardLayoutSaveRequest();
        request.setExpectedVersion(version);
        request.setRowCount(rows);
        request.setColumnCount(columns);
        request.setAisles(List.<DashboardAisleDTO>of());
        request.setOverflowPolicy(policy);
        request.setPositionChanges(List.of(changes));
        return request;
    }

    private DashboardPositionChangeDTO change(long id, int row, int column) {
        DashboardPositionChangeDTO change = new DashboardPositionChangeDTO();
        change.setPrinterId(id);
        change.setGridRow(row);
        change.setGridCol(column);
        return change;
    }
}
