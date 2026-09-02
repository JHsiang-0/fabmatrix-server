package com.example.farm.protocol;

import com.example.farm.common.exception.BusinessException;

/**
 * Farm 支持的打印机协议类型。
 */
public enum PrinterProtocolType {
    KLIPPER,
    RRF;

    /**
     * 规范化数据库中的协议值，兼容历史的 Klipper 大小写写法。
     *
     * @param value 原始协议值
     * @return 规范化协议类型
     */
    public static PrinterProtocolType normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(422, "打印机协议类型不能为空");
        }
        try {
            return value.trim().toUpperCase(java.util.Locale.ROOT).equals("KLIPPER")
                    ? KLIPPER
                    : value.trim().toUpperCase(java.util.Locale.ROOT).equals("RRF")
                    ? RRF
                    : throwUnsupported(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(422, "不支持的打印机协议类型: " + value.trim());
        }
    }

    private static PrinterProtocolType throwUnsupported(String value) {
        throw new BusinessException(422, "不支持的打印机协议类型: " + value.trim());
    }
}
