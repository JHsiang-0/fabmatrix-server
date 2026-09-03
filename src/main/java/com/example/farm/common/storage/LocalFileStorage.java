package com.example.farm.common.storage;

import com.example.farm.common.exception.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** 单进程 Local Edition 文件存储，文件不会暴露为本地绝对路径。 */
@Slf4j
@Component
@Profile("local")
public class LocalFileStorage implements FileStorage {
    private static final String URL_PREFIX = "/api/v1/print-files/storage?key=";

    @Value("${farm.local.storage-path:./data/farm-files}")
    private String storagePath;

    private Path root;

    @PostConstruct
    public void init() {
        try {
            root = Path.of(storagePath).toAbsolutePath().normalize();
            Files.createDirectories(root);
            log.info("Local Edition 文件目录已就绪: path={}", root);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("本地文件目录初始化失败", exception);
        }
    }

    @Override
    public String uploadFile(String key, MultipartFile file) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            return objectUrl(key);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("本地文件上传失败", exception);
        }
    }

    @Override
    public String uploadBytes(String key, byte[] data, String contentType) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return objectUrl(key);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("本地文件写入失败", exception);
        }
    }

    @Override
    public String readHeader(String key) {
        try (InputStream input = Files.newInputStream(resolve(key))) {
            byte[] bytes = input.readNBytes(8192);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            log.warn("读取本地文件头失败: key={}, reason={}", key, exception.getMessage());
            return "";
        }
    }

    @Override
    public InputStreamResource getFileStream(String key) {
        try {
            Path file = resolve(key);
            InputStream input = Files.newInputStream(file);
            return new InputStreamResource(input) {
                @Override
                public String getFilename() {
                    return file.getFileName().toString();
                }

                @Override
                public long contentLength() throws IOException {
                    return Files.size(file);
                }
            };
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("读取本地文件失败", exception);
        }
    }

    @Override
    public void deleteFile(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("本地文件删除失败", exception);
        }
    }

    @Override
    public void deleteFileByObjectUrl(String objectUrl) {
        deleteFile(keyFromLocalUrl(objectUrl));
    }

    @Override
    public String getPresignedUrl(String key, Duration expiration) {
        // Local Edition 由受保护的 HTTP 流接口提供文件，不返回磁盘路径。
        resolve(key);
        return localUrl(key);
    }

    @Override
    public String getPresignedUrlForObjectUrl(String objectUrl, Duration expiration) {
        return getPresignedUrl(keyFromLocalUrl(objectUrl), expiration);
    }

    private String localUrl(String key) {
        return URL_PREFIX + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    private String objectUrl(String key) {
        return "local://" + key;
    }

    private String keyFromLocalUrl(String objectUrl) {
        if (objectUrl == null || !objectUrl.startsWith("local://")) {
            throw new StorageException("本地文件地址格式不正确");
        }
        return objectUrl.substring("local://".length());
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("文件 key 不能为空");
        }
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new StorageException("文件 key 包含非法路径");
        }
        return candidate;
    }
}
