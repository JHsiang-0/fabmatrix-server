package com.example.farm.service;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.common.storage.FileStorage;
import com.example.farm.common.utils.MacAddressUtil;
import com.example.farm.common.utils.PrinterLock;
import com.example.farm.config.PrinterRediscoveryProperties;
import com.example.farm.entity.PrintFile;
import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.mapper.PrintFileMapper;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterNetworkDiscovery;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.impl.PrintJobServiceImpl;
import com.example.farm.service.impl.PrinterControlServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real identity guard with fixture observations; never sends a printer command or opens storage. */
class PrinterActionIdentitySafetyTest {

    private static final Long PRINTER_ID = 703L;
    private static final Long JOB_ID = 1001L;
    private static final String MAC = "AA:BB:CC:DD:EE:01";
    private static final String DEVICE_SECRET = "secret-only-for-a-verified-printer";

    private PrinterService printers;
    private PrintJobMapper jobMapper;
    private PrintFileMapper fileMapper;
    private FileStorage storage;
    private PrinterProtocolAdapterFactory adapters;
    private PrinterProtocolAdapter adapter;
    private PrinterNetworkDiscovery discovery;
    private WebSocketEventPublisher events;
    private PrinterRediscoveryProperties properties;
    private PrintJobServiceImpl jobs;
    private PrinterControlServiceImpl control;
    private Printer printer;
    private PrintJob job;
    private InputStreamResource resource;

    @BeforeEach
    void setUp() {
        printers = mock(PrinterService.class);
        jobMapper = mock(PrintJobMapper.class);
        fileMapper = mock(PrintFileMapper.class);
        storage = mock(FileStorage.class);
        adapters = mock(PrinterProtocolAdapterFactory.class);
        adapter = mock(PrinterProtocolAdapter.class);
        discovery = mock(PrinterNetworkDiscovery.class);
        events = mock(WebSocketEventPublisher.class);
        properties = new PrinterRediscoveryProperties();
        properties.setEnabled(true);
        PrinterIdentityVerifier verifier = new PrinterIdentityVerifier(discovery, new MacAddressUtil(), properties);
        jobs = new PrintJobServiceImpl(jobMapper, fileMapper, printers, storage, adapters, events);
        control = new PrinterControlServiceImpl(printers, adapters, jobs, events);
        ReflectionTestUtils.setField(jobs, "identityVerifier", verifier);
        ReflectionTestUtils.setField(control, "identityVerifier", verifier);

        printer = new Printer();
        printer.setId(PRINTER_ID);
        printer.setName("Fixture printer");
        printer.setIpAddress("192.168.10.21");
        printer.setMacAddress(MAC);
        printer.setFirmwareType("KLIPPER");
        printer.setApiKey(DEVICE_SECRET);
        printer.setStatus("PRINTING");
        printer.setConnectionStatus("ONLINE");
        printer.setCurrentJobId(JOB_ID);
        printer.setIsSafeToPrint(true);
        job = new PrintJob();
        job.setId(JOB_ID);
        job.setUserId(2L);
        job.setFileId(20L);
        job.setPrinterId(PRINTER_ID);
        job.setStatus("PRINTING");
        PrintFile file = new PrintFile();
        file.setId(20L);
        file.setUserId(2L);
        file.setVisibility("PRIVATE");
        file.setOriginalName("fixture.gcode");
        file.setSafeName("safe-fixture.gcode");
        resource = new InputStreamResource(new ByteArrayInputStream(
                "; isolated fixture".getBytes(StandardCharsets.UTF_8)));

        when(jobMapper.selectById(JOB_ID)).thenReturn(job);
        when(jobMapper.updateById(any(PrintJob.class))).thenReturn(1);
        when(fileMapper.selectById(20L)).thenReturn(file);
        when(printers.getById(PRINTER_ID)).thenAnswer(invocation -> {
            assertThat(PrinterLock.isHeld(PRINTER_ID)).isTrue();
            return printer;
        });
        when(printers.updateById(any(Printer.class))).thenReturn(true);
        when(printers.clearJobBinding(PRINTER_ID, JOB_ID)).thenReturn(true);
        when(adapters.getAdapter("KLIPPER")).thenReturn(adapter);
        when(storage.getFileStream("safe-fixture.gcode")).thenReturn(resource);
        when(discovery.identify(any())).thenAnswer(invocation -> {
            PrinterEndpoint endpoint = invocation.getArgument(0);
            assertThat(PrinterLock.isHeld(PRINTER_ID)).isTrue();
            assertThat(endpoint.apiKey()).isEmpty();
            return new PrinterNetworkDiscovery.Identity(endpoint.ipAddress(), "AA:BB:CC:DD:EE:02",
                    PrinterProtocolType.KLIPPER);
        });
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                2L, "fixture-operator", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSE", "EMERGENCY_STOP", "RESUME"})
    void identityFailureRejectsPhysicalControlBeforeSendingCredentialsOrMutatingJobs(String action) {
        if ("RESUME".equals(action)) {
            job.setStatus("PAUSED");
            printer.setStatus("PAUSED");
        }
        String beforeJob = job.getStatus();
        String beforePrinter = printer.getStatus();

        assertIdentityFailure(() -> performControl(action));

        assertUnchanged(beforeJob, beforePrinter, JOB_ID);
        assertNoStateWritesOrDeviceCalls();
        assertCredentialFreeProof();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRINTING", "PAUSED", "UPLOADING"})
    void identityFailureRejectsDeviceCancellationWithoutReconciliationOrUnbinding(String status) {
        job.setStatus(status);

        assertIdentityFailure(() -> control.cancelCurrentJob(PRINTER_ID));

        assertUnchanged(status, "PRINTING", JOB_ID);
        verify(printers, never()).clearJobBinding(any(), any());
        assertNoStateWritesOrDeviceCalls();
        assertCredentialFreeProof();
    }

    @ParameterizedTest
    @MethodSource("startCases")
    void identityFailureRejectsStartAndUploadBeforeChangingAssignedOrReadyTasks(String status, String action) {
        job.setStatus(status);
        printer.setStatus("PREPARING");

        assertIdentityFailure(() -> jobs.startPrint(JOB_ID, 2L, action));

        assertUnchanged(status, "PREPARING", JOB_ID);
        assertNoStateWritesOrDeviceCalls();
        assertCredentialFreeProof();
    }

    @Test
    void compatibilityAssignmentChecksIdentityBeforeAssigningOrBindingTheTask() {
        job.setStatus("QUEUED");
        job.setPrinterId(null);
        printer.setStatus("IDLE");
        printer.setCurrentJobId(null);

        assertIdentityFailure(() -> jobs.assignAndStartPrint(JOB_ID, PRINTER_ID));

        assertThat(job.getStatus()).isEqualTo("QUEUED");
        assertThat(job.getPrinterId()).isNull();
        assertUnchanged("QUEUED", "IDLE", null);
        verify(printers, never()).bindJobIfIdle(any(), any());
        verify(jobMapper, never()).assignJobIfQueued(any(), any(), any(), any());
        assertNoStateWritesOrDeviceCalls();
        assertCredentialFreeProof();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSE", "EMERGENCY_STOP", "RESUME"})
    void matchingIdentityLetsPhysicalControlUseTheLatestDatabaseIp(String action) {
        acceptIdentity();
        if ("RESUME".equals(action)) {
            job.setStatus("PAUSED");
            printer.setStatus("PAUSED");
        }

        performControl(action);

        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        switch (action) {
            case "PAUSE" -> verify(adapter).pause(endpoint.capture());
            case "EMERGENCY_STOP" -> verify(adapter).emergencyStop(endpoint.capture());
            case "RESUME" -> verify(adapter).resume(endpoint.capture());
            default -> throw new IllegalArgumentException(action);
        }
        assertVerifiedEndpoint(endpoint.getValue());
        assertCredentialFreeProof();
        verify(printers).getById(PRINTER_ID);
    }

    @ParameterizedTest
    @MethodSource("startCases")
    void matchingIdentityAllowsStartAndUploadWithTheLatestAddress(String status, String action) {
        acceptIdentity();
        job.setStatus(status);
        printer.setStatus("PREPARING");

        jobs.startPrint(JOB_ID, 2L, action);

        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        if ("READY".equals(status)) {
            verify(adapter).startFile(endpoint.capture(), eq("fixture.gcode"));
            verifyNoInteractions(storage);
        } else {
            verify(adapter).uploadFile(endpoint.capture(), eq(resource), eq("fixture.gcode"),
                    eq(!"UPLOAD_ONLY".equals(action)));
        }
        assertVerifiedEndpoint(endpoint.getValue());
        assertCredentialFreeProof();
        assertThat(job.getStatus()).isEqualTo("UPLOAD_ONLY".equals(action) ? "READY" : "PRINTING");
        assertThat(printer.getIsSafeToPrint()).isFalse();
    }

    @Test
    void matchingIdentityAllowsDeviceCancellationThenReleasesItsBinding() {
        acceptIdentity();

        control.cancelCurrentJob(PRINTER_ID);

        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(adapter).cancel(endpoint.capture());
        assertVerifiedEndpoint(endpoint.getValue());
        assertCredentialFreeProof();
        assertThat(job.getStatus()).isEqualTo("CANCELLED");
        assertThat(printer.getCurrentJobId()).isNull();
        assertThat(printer.getIsSafeToPrint()).isFalse();
        verify(printers).clearJobBinding(PRINTER_ID, JOB_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASSIGNED", "READY"})
    void cancellingAPreparedTaskRequiresNoDeviceIdentityOrProtocolAction(String status) {
        job.setStatus(status);
        printer.setStatus("PREPARING");

        control.cancelCurrentJob(PRINTER_ID);

        assertThat(job.getStatus()).isEqualTo("CANCELLED");
        assertThat(printer.getCurrentJobId()).isNull();
        verify(printers).clearJobBinding(PRINTER_ID, JOB_ID);
        verifyNoInteractions(discovery, adapters, adapter, storage);
    }

    @Test
    void disabledRediscoveryKeepsExistingPhysicalControlCompatibility() {
        properties.setEnabled(false);

        control.pause(PRINTER_ID);

        verify(adapter).pause(any());
        verifyNoInteractions(discovery);
    }

    private void acceptIdentity() {
        // 避免重写桩时触发 setup 中只适用于真实请求的设备锁断言。
        doReturn(new PrinterNetworkDiscovery.Identity(printer.getIpAddress(), MAC, PrinterProtocolType.KLIPPER))
                .when(discovery).identify(any());
    }

    private void performControl(String action) {
        switch (action) {
            case "PAUSE" -> control.pause(PRINTER_ID);
            case "EMERGENCY_STOP" -> control.emergencyStop(PRINTER_ID);
            case "RESUME" -> control.resume(PRINTER_ID);
            default -> throw new IllegalArgumentException(action);
        }
    }

    private void assertUnchanged(String jobStatus, String printerStatus, Long binding) {
        assertThat(job.getStatus()).isEqualTo(jobStatus);
        assertThat(printer.getStatus()).isEqualTo(printerStatus);
        assertThat(printer.getCurrentJobId()).isEqualTo(binding);
        assertThat(printer.getIsSafeToPrint()).isTrue();
    }

    private void assertNoStateWritesOrDeviceCalls() {
        verify(jobMapper, never()).updateById(any(PrintJob.class));
        verify(printers, never()).updateById(any(Printer.class));
        verifyNoInteractions(adapters, adapter, storage, events);
    }

    private void assertCredentialFreeProof() {
        ArgumentCaptor<PrinterEndpoint> endpoint = ArgumentCaptor.forClass(PrinterEndpoint.class);
        verify(discovery).identify(endpoint.capture());
        assertThat(endpoint.getValue().apiKey()).isEmpty();
        assertThat(endpoint.getValue().ipAddress()).isEqualTo("192.168.10.21");
    }

    private static void assertVerifiedEndpoint(PrinterEndpoint endpoint) {
        assertThat(endpoint.printerId()).isEqualTo(PRINTER_ID);
        assertThat(endpoint.ipAddress()).isEqualTo("192.168.10.21");
        assertThat(endpoint.apiKey()).isEqualTo(DEVICE_SECRET);
    }

    private static void assertIdentityFailure(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(503));
    }

    private static Stream<Arguments> startCases() {
        return Stream.of(Arguments.of("ASSIGNED", "START_PRINT"),
                Arguments.of("READY", "START_PRINT"), Arguments.of("ASSIGNED", "UPLOAD_ONLY"));
    }
}
