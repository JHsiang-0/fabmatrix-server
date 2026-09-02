package com.example.farm.service.impl;

import com.example.farm.common.exception.BusinessException;
import com.example.farm.entity.Printer;
import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolAdapter;
import com.example.farm.protocol.PrinterProtocolAdapterFactory;
import com.example.farm.protocol.PrinterProtocolException;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.service.PrinterControlService;
import com.example.farm.service.PrinterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 通过协议适配器执行打印机控制操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrinterControlServiceImpl implements PrinterControlService {

    private final PrinterService printerService;
    private final PrinterProtocolAdapterFactory adapterFactory;

    @Override
    public void emergencyStop(Long printerId) {
        PrinterEndpoint endpoint = endpointOf(printerId, PrinterOperation.EMERGENCY_STOP);
        adapter(endpoint, PrinterOperation.EMERGENCY_STOP).emergencyStop(endpoint);
        log.warn("急停执行成功: printerId={}", printerId);
    }

    @Override
    public void pause(Long printerId) {
        PrinterEndpoint endpoint = endpointOf(printerId, PrinterOperation.PAUSE);
        adapter(endpoint, PrinterOperation.PAUSE).pause(endpoint);
        log.info("暂停打印执行成功: printerId={}", printerId);
    }

    private PrinterEndpoint endpointOf(Long printerId, PrinterOperation operation) {
        if (printerId == null || printerId <= 0) {
            throw new BusinessException(400, "打印机 ID 必须为正数");
        }
        Printer printer = printerService.getById(printerId);
        if (printer == null) {
            throw new BusinessException(404, "打印机不存在");
        }
        if ("OFFLINE".equalsIgnoreCase(printer.getStatus())) {
            throw new BusinessException(10001, "打印机当前离线");
        }
        if (printer.getIpAddress() == null || printer.getIpAddress().isBlank()) {
            throw new BusinessException(10001, "打印机没有可用的网络地址");
        }
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(printer.getFirmwareType());
        return new PrinterEndpoint(printer.getId(), printer.getIpAddress(), printer.getApiKey(), protocolType);
    }

    private PrinterProtocolAdapter adapter(PrinterEndpoint endpoint, PrinterOperation operation) {
        try {
            return adapterFactory.getAdapter(endpoint.protocolType().name());
        } catch (PrinterProtocolException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("打印机协议选择失败: printerId={}, operation={}, protocol={}",
                    endpoint.printerId(), operation, endpoint.protocolType());
            throw new BusinessException(422, "打印机协议暂不支持当前操作");
        }
    }
}
