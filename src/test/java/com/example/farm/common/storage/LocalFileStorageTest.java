package com.example.farm.common.storage;

import com.example.farm.common.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage();
        ReflectionTestUtils.setField(storage, "storagePath", tempDir.toString());
        ReflectionTestUtils.setField(storage, "safetyMargin", DataSize.ofBytes(0));
        storage.init();
    }

    @Test
    void uploadsAtomicallyAndDoesNotOverwriteExistingTarget() throws Exception {
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        storage.uploadFile("uploads/demo.gcode", new MockMultipartFile(
                "file", "demo.gcode", "text/plain", original));

        assertThat(Files.readAllBytes(tempDir.resolve("uploads/demo.gcode"))).isEqualTo(original);
        assertThatThrownBy(() -> storage.uploadBytes(
                "uploads/demo.gcode", "replacement".getBytes(StandardCharsets.UTF_8), "text/plain"))
                .isInstanceOf(StorageException.class);
        assertThat(Files.readAllBytes(tempDir.resolve("uploads/demo.gcode"))).isEqualTo(original);
        assertThat(uploadTempFileCount()).isZero();
    }

    @Test
    void removesTemporaryFileWhenMultipartTransferFails() throws Exception {
        MultipartFile failingFile = new MockMultipartFile(
                "file", "broken.gcode", "text/plain", "partial".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void transferTo(Path destination) throws IOException {
                Files.write(destination, "partial".getBytes(StandardCharsets.UTF_8));
                throw new IOException("simulated transfer failure");
            }
        };

        assertThatThrownBy(() -> storage.uploadFile("uploads/broken.gcode", failingFile))
                .isInstanceOf(StorageException.class);
        assertThat(Files.exists(tempDir.resolve("uploads/broken.gcode"))).isFalse();
        assertThat(uploadTempFileCount()).isZero();
    }

    @Test
    void rejectsTraversalAndBackslashPaths() {
        assertThatThrownBy(() -> storage.uploadBytes("../outside.gcode", new byte[]{1}, "text/plain"))
                .isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.uploadBytes("uploads\\outside.gcode", new byte[]{1}, "text/plain"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void rejectsSymbolicLinkPath() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path link = tempDir.resolve("uploads");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "当前文件系统不支持符号链接测试");
        }

        assertThatThrownBy(() -> storage.uploadBytes("uploads/escape.gcode", new byte[]{1}, "text/plain"))
                .isInstanceOf(StorageException.class);
    }

    private long uploadTempFileCount() throws IOException {
        try (var paths = Files.walk(tempDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".upload"))
                    .count();
        }
    }
}
