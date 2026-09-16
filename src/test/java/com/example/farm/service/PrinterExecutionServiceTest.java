package com.example.farm.service;

import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.mapper.PrintJobMapper;
import com.example.farm.mapper.PrinterExecutionMapper;
import com.example.farm.mapper.PrinterMapper;
import com.example.farm.protocol.PrinterDeviceStatus;
import com.example.farm.protocol.PrinterStatus;
import com.example.farm.service.impl.PrinterExecutionServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrinterExecutionServiceTest {

    @Mock
    private PrinterExecutionMapper executionMapper;
    @Mock
    private PrintJobMapper printJobMapper;
    @Mock
    private PrinterMapper printerMapper;

    @Test
    void persistsProcessingEvidenceAndUsesItForAtomicCompletion() {
        PrinterExecutionServiceImpl service = new PrinterExecutionServiceImpl(
                executionMapper, printJobMapper, printerMapper);
        when(executionMapper.selectOne(any())).thenReturn(null);
        when(executionMapper.insert(any(PrinterExecution.class))).thenAnswer(invocation -> {
            PrinterExecution execution = invocation.getArgument(0);
            execution.setId(71L);
            return 1;
        });

        PrinterExecution execution = service.observeExecuting(
                4L, 9L, "FARM", "part.gcode", processingStatus());

        assertThat(execution.getHasSeenProcessing()).isTrue();
        assertThat(execution.getObservedFilename()).isEqualTo("0:/gcodes/part.gcode");
        assertThat(execution.getMaxFilePosition()).isEqualByComparingTo("200");

        Printer printer = new Printer();
        printer.setId(4L);
        printer.setCurrentJobId(9L);
        PrintJob job = new PrintJob();
        job.setId(9L);
        job.setPrinterId(4L);
        job.setStatus("PRINTING");
        when(printJobMapper.selectById(9L)).thenReturn(job);
        when(printerMapper.selectById(4L)).thenReturn(printer);
        when(printJobMapper.updateById(job)).thenReturn(1);
        when(printerMapper.clearJobBinding(4L, 9L)).thenReturn(1);
        when(executionMapper.updateById(execution)).thenReturn(1);

        assertThat(service.finishFarmExecution(
                execution, printer, job, PrintJobStatus.COMPLETED, null)).isTrue();

        assertThat(job.getStatus()).isEqualTo("COMPLETED");
        assertThat(job.getProgress()).isEqualByComparingTo("100");
        assertThat(printer.getCurrentJobId()).isNull();
        assertThat(execution.getState()).isEqualTo("COMPLETED");
        verify(printJobMapper).updateById(job);
        verify(printerMapper).clearJobBinding(4L, 9L);
        verify(executionMapper).updateById(execution);
    }

    @Test
    void detectsDeviceRestartFromPersistedUptime() {
        PrinterExecutionServiceImpl service = new PrinterExecutionServiceImpl(
                executionMapper, printJobMapper, printerMapper);
        PrinterExecution execution = new PrinterExecution();
        execution.setDeviceUptimeAtStart(500L);

        assertThat(service.deviceRestarted(execution, statusWithUptime(120))).isTrue();
        assertThat(service.deviceRestarted(execution, statusWithUptime(800))).isFalse();
    }

    private PrinterDeviceStatus processingStatus() {
        return new PrinterDeviceStatus(
                PrinterStatus.PRINTING, "processing", null, "0:/gcodes/part.gcode",
                BigDecimal.TEN, null, null, null, null, null, null, null,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1000), null,
                false, false, null, BigDecimal.valueOf(500));
    }

    private PrinterDeviceStatus statusWithUptime(long uptime) {
        return new PrinterDeviceStatus(
                PrinterStatus.IDLE, "idle", null, "part.gcode",
                null, null, null, null, null, null, null, null,
                null, null, null, false, false, null, BigDecimal.valueOf(uptime));
    }
}
