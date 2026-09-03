package com.example.farm.common.storage;

import org.springframework.core.io.InputStreamResource;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;

/**
 * Farm 文件存储端口。业务层只依赖该接口，Server Edition 和 Local Edition
 * 分别提供 RustFS 与本地目录实现。
 */
public interface FileStorage {
    String uploadFile(String key, MultipartFile file);

    String uploadBytes(String key, byte[] data, String contentType);

    String readHeader(String key);

    InputStreamResource getFileStream(String key);

    void deleteFile(String key);

    void deleteFileByObjectUrl(String objectUrl);

    String getPresignedUrl(String key, Duration expiration);

    String getPresignedUrlForObjectUrl(String objectUrl, Duration expiration);
}
