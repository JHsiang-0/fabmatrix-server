package com.example.farm.service;

import com.example.farm.entity.DashboardLayout;
import com.example.farm.entity.Printer;
import com.example.farm.entity.dto.DashboardAisleDTO;
import com.example.farm.entity.dto.DashboardLayoutSaveRequest;
import com.example.farm.entity.dto.DashboardOverflowPolicy;
import com.example.farm.entity.dto.DashboardPositionChangeDTO;
import com.example.farm.entity.dto.PrinterPositionUpdateDTO;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

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

    @Test
    void legacyPositionsUseLockedLayoutSaveAndKeepOldSuccessShape() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        DashboardLayout layout = layout(4L, 4, 12);
        Printer printer = printer(11L, "first", 1, 1);
        when(layoutMapper.selectById(1L)).thenReturn(layout);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout);
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of(printer));
        when(printerMapper.clearPrinterPositions(any())).thenReturn(1);
        when(printerMapper.updatePrinterPosition(any(), any(), any())).thenReturn(1);
        when(layoutMapper.updateById(any(DashboardLayout.class))).thenReturn(1);
        admin();

        PrinterPositionUpdateDTO legacy = new PrinterPositionUpdateDTO();
        legacy.setId(11L);
        legacy.setGridRow(2);
        legacy.setGridCol(3);

        DashboardLayoutSaveResultVO result = service(layoutMapper, printerMapper)
                .saveLegacyPositions(List.of(legacy));

        assertThat(result.getLayout().getVersion()).isEqualTo(5L);
        assertThat(result.getChangedPositions()).singleElement().satisfies(position -> {
            assertThat(position.getPrinterId()).isEqualTo(11L);
            assertThat(position.getGridRow()).isEqualTo(2);
            assertThat(position.getGridCol()).isEqualTo(3);
        });
        verify(layoutMapper).selectForUpdate(1L);
        verify(printerMapper).clearPrinterPositions(any());
    }

    @Test
    void rejectsUnknownPrinterBeforeAnyWrite() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(1L, 4, 12));
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of());
        admin();

        assertThatThrownBy(() -> service(layoutMapper, printerMapper).saveLayout(
                request(1L, 4, 12, DashboardOverflowPolicy.REJECT, change(99L, 1, 1))))
                .hasMessageContaining("PRINTER_NOT_FOUND")
                .extracting("code").isEqualTo(404L);
        verifyNoInteractions(printerMapper);
        verify(layoutMapper, never()).updateById((DashboardLayout) any());
    }

    @Test
    void rejectsPartialCoordinatesAndDuplicateFinalSlotsBeforeWrites() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(1L, 4, 12));
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of(
                printer(11L, "first", 1, 1), printer(12L, "second", 1, 2)));
        admin();

        DashboardPositionChangeDTO partial = change(11L, 1, null);
        assertThatThrownBy(() -> service(layoutMapper, printerMapper).saveLayout(
                request(1L, 4, 12, DashboardOverflowPolicy.REJECT, partial)))
                .hasMessageContaining("INVALID_COORDINATE");

        DashboardPositionChangeDTO occupied = change(11L, 1, 2);
        assertThatThrownBy(() -> service(layoutMapper, printerMapper).saveLayout(
                request(1L, 4, 12, DashboardOverflowPolicy.REJECT, occupied)))
                .hasMessageContaining("DUPLICATE_SLOT")
                .extracting("problem.conflicts").asList().contains(11L, 12L);
        verifyNoInteractions(printerMapper);
    }

    @Test
    void rejectsMoreThanOneHundredExplicitChanges() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        DashboardLayoutSaveRequest request = request(1L, 4, 12, DashboardOverflowPolicy.REJECT);
        request.setPositionChanges(java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(id -> change(id, null, null)).toList());
        admin();

        assertThatThrownBy(() -> service(layoutMapper, printerMapper).saveLayout(request))
                .hasMessageContaining("CELL_LIMIT_EXCEEDED").extracting("code").isEqualTo(400L);
        verifyNoInteractions(layoutMapper, printerMapper);
    }

    @Test
    void publishesAuditAndLayoutEventOnlyAfterCommit() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebSocketEventPublisher eventPublisher = mock(WebSocketEventPublisher.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(1L, 4, 12));
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of());
        when(layoutMapper.updateById(any(DashboardLayout.class))).thenReturn(1);
        DashboardLayoutServiceImpl target = service(layoutMapper, printerMapper);
        ReflectionTestUtils.setField(target, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(target, "webSocketEventPublisher", eventPublisher);
        admin();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            target.saveLayout(request(1L, 4, 12, DashboardOverflowPolicy.REJECT));
            verifyNoInteractions(auditLogService, eventPublisher);
            TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
            verify(auditLogService).recordSuccess("DASHBOARD_LAYOUT_UPDATE", "DASHBOARD_LAYOUT", 1L,
                    "动态看板布局 v2");
            verify(eventPublisher).publishLayoutUpdatedAfterCommit(2L);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotPublishSuccessSideEffects() {
        DashboardLayoutMapper layoutMapper = mock(DashboardLayoutMapper.class);
        PrinterMapper printerMapper = mock(PrinterMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        WebSocketEventPublisher eventPublisher = mock(WebSocketEventPublisher.class);
        when(layoutMapper.selectForUpdate(1L)).thenReturn(layout(1L, 4, 12));
        when(layoutMapper.selectPrintersForUpdate()).thenReturn(List.of());
        doThrow(new RuntimeException("database failure")).when(layoutMapper)
                .updateById((DashboardLayout) any());
        DashboardLayoutServiceImpl target = service(layoutMapper, printerMapper);
        ReflectionTestUtils.setField(target, "auditLogService", auditLogService);
        ReflectionTestUtils.setField(target, "webSocketEventPublisher", eventPublisher);
        admin();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> target.saveLayout(
                    request(1L, 4, 12, DashboardOverflowPolicy.REJECT))).isInstanceOf(RuntimeException.class);
            verifyNoInteractions(auditLogService, eventPublisher);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
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

    private DashboardPositionChangeDTO change(long id, Integer row, Integer column) {
        DashboardPositionChangeDTO change = new DashboardPositionChangeDTO();
        change.setPrinterId(id);
        change.setGridRow(row);
        change.setGridCol(column);
        return change;
    }
}
