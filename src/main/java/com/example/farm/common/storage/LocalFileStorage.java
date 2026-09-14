package com.example.farm.common.storage;

import com.example.farm.common.exception.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    @Value("${farm.local.storage-safety-margin:256MB}")
    private DataSize safetyMargin;

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
        Path temporary = null;
        try {
            ensureCapacity(file.getSize());
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".upload");
            file.transferTo(temporary);
            verifyUploadedSize(temporary, file.getSize());
            moveAtomicallyWithoutReplacement(temporary, target);
            return objectUrl(key);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("本地文件上传失败", exception);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    @Override
    public String uploadBytes(String key, byte[] data, String contentType) {
        Path temporary = null;
        try {
            if (data == null) {
                throw new StorageException("本地文件写入内容不能为空");
            }
            ensureCapacity(data.length);
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".upload");
            Files.write(temporary, data);
            verifyUploadedSize(temporary, data.length);
            moveAtomicallyWithoutReplacement(temporary, target);
            return objectUrl(key);
        } catch (IOException | RuntimeException exception) {
            throw new StorageException("本地文件写入失败", exception);
        } finally {
            deleteTemporaryFile(temporary);
        }
    }

    @Override
    public void ensureCapacity(long incomingBytes) {
        if (incomingBytes <= 0) {
            throw new StorageException("文件大小必须大于零");
        }
        try {
            long marginBytes = safetyMargin == null ? DataSize.ofMegabytes(256).toBytes() : safetyMargin.toBytes();
            if (marginBytes < 0) {
                throw new StorageException("本地磁盘安全余量不能为负数");
            }
            long required = Math.addExact(incomingBytes, marginBytes);
            long usable = Files.getFileStore(root).getUsableSpace();
            if (usable < required) {
                throw new StorageException("本地磁盘空间不足，请清理空间后重试");
            }
        } catch (IOException | ArithmeticException exception) {
            throw new StorageException("无法检查本地磁盘可用空间", exception);
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
        if (key.indexOf('\\') >= 0 || key.chars().anyMatch(Character::isISOControl)) {
            throw new StorageException("文件 key 包含非法路径");
        }
        Path candidate = root.resolve(key).normalize();
        if (!candidate.startsWith(root)) {
            throw new StorageException("文件 key 包含非法路径");
        }
        validateExistingPath(candidate);
        return candidate;
    }

    private void validateExistingPath(Path candidate) {
        Path current = candidate;
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) {
                throw new StorageException("文件 key 包含非法链接");
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
    }

    private void verifyUploadedSize(Path temporary, long expectedSize) throws IOException {
        if (Files.size(temporary) != expectedSize) {
            throw new IOException("临时文件大小与上传声明不一致");
        }
    }

    private void moveAtomicallyWithoutReplacement(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void deleteTemporaryFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            log.warn("清理本地临时上传文件失败: path={}", temporary);
        }
    }
}
