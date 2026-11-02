package com.example.farm.common.storage;

import org.springframework.core.io.InputStreamResource;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * Farm 文件存储端口，当前使用本地目录实现。
 */
public interface FileStorage {
    String uploadFile(String key, MultipartFile file);

    String uploadBytes(String key, byte[] data, String contentType);

    String readHeader(String key);

    InputStreamResource getFileStream(String key);

    void deleteFile(String key);

    void deleteFileByObjectUrl(String objectUrl);

    /** 落盘前检查可用磁盘空间。 */
    default void ensureCapacity(long incomingBytes) {
        // 未提供预检的测试替身可保持空实现。
    }

    String getPresignedUrl(String key, Duration expiration);

    String getPresignedUrlForObjectUrl(String objectUrl, Duration expiration);
}
