package com.example.farm.service;

import com.example.farm.entity.PrintJob;
import com.example.farm.entity.Printer;
import com.example.farm.entity.PrinterExecution;
import com.example.farm.entity.enums.PrintJobStatus;
import com.example.farm.protocol.PrinterDeviceStatus;

public interface PrinterExecutionService {

    PrinterExecution findActive(Long printerId);

    PrinterExecution beginFarmExecution(Long printerId, Long jobId, String expectedFilename,
                                        PrinterDeviceStatus confirmedStatus);

    PrinterExecution observeExecuting(Long printerId, Long farmJobId, String source,
                                      String expectedFilename, PrinterDeviceStatus status);

    PrinterExecution observeIdle(Long printerId, PrinterDeviceStatus status);

    boolean deviceRestarted(PrinterExecution execution, PrinterDeviceStatus status);

    boolean finishFarmExecution(PrinterExecution execution, Printer printer, PrintJob job,
                                PrintJobStatus target, String reason);

    boolean finishNonFarmExecution(PrinterExecution execution, String state, String reason);
}
