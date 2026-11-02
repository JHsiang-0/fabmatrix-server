package com.example.farm.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 防止多个服务进程同时使用同一个 SQLite 数据库和各自独立的内存锁。 */
public final class SqliteInstanceGuard implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    public SqliteInstanceGuard(String url) throws IOException {
        if (!url.startsWith("jdbc:sqlite:") || url.contains("?")) {
            throw new IllegalArgumentException("本地数据库必须使用普通 SQLite 文件路径");
        }
        String file = url.substring("jdbc:sqlite:".length());
        if (file.isBlank() || file.equals(":memory:") || file.startsWith("file:")) {
            throw new IllegalArgumentException("本地数据库必须使用普通 SQLite 文件路径");
        }
        Path database = Path.of(file).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        channel = FileChannel.open(database.resolveSibling(database.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IllegalStateException("数据库已被另一个 Server 实例使用: " + database);
        } catch (OverlappingFileLockException failure) {
            channel.close();
            throw new IllegalStateException("数据库已被另一个 Server 实例使用: " + database, failure);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try { lock.release(); } finally { channel.close(); }
    }
}
