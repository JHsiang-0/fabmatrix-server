package com.example.farm.protocol;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 根据打印机协议类型选择唯一的协议适配器。
 */
@Component
public class PrinterProtocolAdapterFactory {

    private final Map<PrinterProtocolType, PrinterProtocolAdapter> adapters;

    public PrinterProtocolAdapterFactory(List<PrinterProtocolAdapter> adapterList) {
        this.adapters = new EnumMap<>(PrinterProtocolType.class);
        if (adapterList == null) {
            return;
        }
        for (PrinterProtocolAdapter adapter : adapterList) {
            if (adapter == null || adapter.protocolType() == null) {
                throw new IllegalStateException("协议适配器必须声明协议类型");
            }
            PrinterProtocolAdapter previous = this.adapters.putIfAbsent(adapter.protocolType(), adapter);
            if (previous != null) {
                throw new IllegalStateException("同一协议不能注册多个打印机适配器: " + adapter.protocolType());
            }
        }
    }

    public PrinterProtocolAdapter getAdapter(String firmwareType) {
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(firmwareType);
        PrinterProtocolAdapter adapter = adapters.get(protocolType);
        if (adapter == null) {
            throw new PrinterProtocolException(
                    PrinterOperation.GET_STATUS,
                    protocolType,
                    FailureCategory.UNSUPPORTED,
                    "打印机协议暂未接入: " + protocolType
            );
        }
        return adapter;
    }

    public boolean supports(String firmwareType) {
        PrinterProtocolType protocolType = PrinterProtocolType.normalize(firmwareType);
        return adapters.containsKey(protocolType);
    }
}
