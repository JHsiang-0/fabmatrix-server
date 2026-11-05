package com.example.farm.cloud;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;

public final class CloudAgentFiles {
    private CloudAgentFiles() { }

    public static void verifyWritable(Path target) throws IOException {
        if (Files.exists(target) && !Files.isRegularFile(target)) {
            throw new IOException("Cloud agent configuration target must be a regular file");
        }
        Path parent = target.toAbsolutePath().normalize().getParent();
        Files.createDirectories(parent);
        Path probe = Files.createTempFile(parent, ".cloud-agent-check-", ".tmp");
        try { restrict(probe); }
        finally { Files.deleteIfExists(probe); }
    }

    public static String read(Path path, int maximumBytes) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) { throw new IOException("Cloud agent file exceeds size limit"); }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static void write(Path path, String text) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".cloud-agent-", ".tmp");
        try {
            restrict(temporary);
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void restrict(Path path) throws IOException {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) { throw new IOException("Cloud agent requires protected credential storage"); }
        var owner = Files.getOwner(path);
        var entries = new java.util.ArrayList<AclEntry>();
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        try {
            var system = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("NT AUTHORITY\\SYSTEM");
            entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(system)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
        } catch (UserPrincipalNotFoundException ignored) { }
        acl.setAcl(List.copyOf(entries));
    }
}
