package com.example.farm.protocol;

import lombok.Getter;

/**
 * 协议适配层的统一异常，不向客户端暴露底层协议异常原文。
 */
@Getter
public class PrinterProtocolException extends RuntimeException {

    private final PrinterOperation operation;
    private final PrinterProtocolType protocolType;
    private final FailureCategory category;

    public PrinterProtocolException(PrinterOperation operation,
                                    PrinterProtocolType protocolType,
                                    FailureCategory category,
                                    String message) {
        super(message);
        this.operation = operation;
        this.protocolType = protocolType;
        this.category = category;
    }

    public PrinterProtocolException(PrinterOperation operation,
                                    PrinterProtocolType protocolType,
                                    FailureCategory category,
                                    String message,
                                    Throwable cause) {
        super(message, cause);
        this.operation = operation;
        this.protocolType = protocolType;
        this.category = category;
    }
}
