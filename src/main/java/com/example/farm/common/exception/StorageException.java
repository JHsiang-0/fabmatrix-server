package com.example.farm.common.exception;

/**
 * 对象存储访问失败，统一转换为存储服务错误响应。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
