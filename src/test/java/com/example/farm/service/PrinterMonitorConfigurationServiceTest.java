package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterProtocolDetector;
import com.example.farm.protocol.RrfApiClient;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.service.impl.PrinterServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PrinterMonitorConfigurationServiceTest {

    private PrinterMapper mapper;
    private PrinterCacheService cache;
    private PrinterServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PrinterMapper.class);
        cache = mock(PrinterCacheService.class);
        service = new PrinterServiceImpl(cache, mock(MacAddressUtil.class),
                mock(PrinterProtocolDetector.class), mock(RrfApiClient.class));
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        authenticate("ADMIN");
    }

    @AfterEach
    void clearThreadState() {
        SecurityContextHolder.clearContext();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"OPERATOR"})
    void serviceRejectsMonitorWritesWithoutAdminRole(String role) {
        if (role == null) {
            SecurityContextHolder.clearContext();
        } else {
            authenticate(role);
        }

        assertCode(() -> service.updateMonitorEnabled(1L, true), 403);
        assertCode(() -> service.batchUpdateMonitorEnabled(List.of(1L), true), 403);
        verifyNoInteractions(mapper, cache);
    }

    @Test
    void singleUpdateRejectsMissingOrUnknownIdBeforeWriting() {
        assertCode(() -> service.updateMonitorEnabled(null, true), 400);
        assertCode(() -> service.updateMonitorEnabled(99L, true), 404);

        verify(mapper, never()).updateById(any(Printer.class));
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @MethodSource("invalidBatchIds")
    void invalidBatchIdsCannotBeSilentlyIgnored(List<Long> ids) {
        assertCode(() -> service.batchUpdateMonitorEnabled(ids, true), 400);
        verifyNoInteractions(mapper, cache);
    }

    static Stream<List<Long>> invalidBatchIds() {
        return Stream.of(null, List.of(), Arrays.asList(1L, null),
                LongStream.rangeClosed(1, 101).boxed().toList());
    }

    @Test
    void monitorUpdateDoesNotWriteTheLoadedPrinterSnapshot() {
        Printer snapshot = new Printer();
        snapshot.setId(1L);
        snapshot.setCurrentJobId(101L);
        snapshot.setStatus("PRINTING");
        snapshot.setIsSafeToPrint(true);
        snapshot.setConnectionStatus("ONLINE");
        snapshot.setIpAddress("192.168.1.80");
        when(mapper.selectById(1L)).thenReturn(snapshot);
        when(mapper.updateById(any(Printer.class))).thenReturn(1);

        service.updateMonitorEnabled(1L, false);

        ArgumentCaptor<Printer> patch = ArgumentCaptor.forClass(Printer.class);
        verify(mapper).updateById(patch.capture());
        assertThat(patch.getValue()).isNotSameAs(snapshot);
        assertThat(patch.getValue().getId()).isEqualTo(1L);
        assertThat(patch.getValue().getMonitorEnabled()).isFalse();
        assertThat(patch.getValue().getCurrentJobId()).isNull();
        assertThat(patch.getValue().getStatus()).isNull();
        assertThat(patch.getValue().getIsSafeToPrint()).isNull();
        assertThat(patch.getValue().getConnectionStatus()).isNull();
        assertThat(patch.getValue().getIpAddress()).isNull();
        verify(cache).refreshPrinterCache();
    }

    @Test
    void zeroChangedRowsWithDifferentStoredSwitchStillReportConflict() {
        Printer printer = new Printer();
        printer.setId(1L);
        when(mapper.selectById(1L)).thenReturn(printer);
        when(mapper.updateById(any(Printer.class))).thenReturn(0);
        when(mapper.selectMonitorEnabledForUpdate(1L)).thenReturn(false);

        assertCode(() -> service.updateMonitorEnabled(1L, true), 409);
        verifyNoInteractions(cache);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void zeroChangedRowsAreIdempotentOnlyWhenStoredSwitchAlreadyMatches(boolean enabled) {
        Printer printer = new Printer();
        printer.setId(1L);
        printer.setMonitorEnabled(enabled);
        when(mapper.selectById(1L)).thenReturn(printer);
        when(mapper.updateById(any(Printer.class))).thenReturn(0);
        when(mapper.selectMonitorEnabledForUpdate(1L)).thenReturn(enabled);

        service.updateMonitorEnabled(1L, enabled);

        verify(mapper).selectMonitorEnabledForUpdate(1L);
        verify(cache).refreshPrinterCache();
    }

    @Test
    void printerDeletedAfterInitialReadStillReportsConflict() {
        Printer printer = new Printer();
        printer.setId(1L);
        printer.setMonitorEnabled(true);
        when(mapper.selectById(1L)).thenReturn(printer);
        when(mapper.updateById(any(Printer.class))).thenReturn(0);
        when(mapper.selectMonitorEnabledForUpdate(1L)).thenReturn(null);

        assertCode(() -> service.updateMonitorEnabled(1L, true), 409);
        verifyNoInteractions(cache);
    }

    @Test
    void cacheRefreshWaitsUntilTransactionCommits() {
        prepareSuccessfulUpdate();
        beginTransactionSynchronization();

        service.updateMonitorEnabled(1L, true);

        verifyNoInteractions(cache);
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(cache).refreshPrinterCache();
    }

    @Test
    void rolledBackMonitorWriteDoesNotInvalidateCache() {
        prepareSuccessfulUpdate();
        beginTransactionSynchronization();

        service.updateMonitorEnabled(1L, true);
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(cache);
    }

    private void prepareSuccessfulUpdate() {
        Printer printer = new Printer();
        printer.setId(1L);
        when(mapper.selectById(1L)).thenReturn(printer);
        when(mapper.updateById(any(Printer.class))).thenReturn(1);
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, "admin",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void assertCode(Runnable action, long code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(code);
    }
}
