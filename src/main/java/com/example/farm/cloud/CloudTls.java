package com.example.farm.cloud;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class CloudTls {
    private CloudTls() { }

    public static URI apiUri(String value) {
        URI uri = URI.create(value);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost())
                || "[::1]".equals(uri.getHost()) || "::1".equals(uri.getHost());
        if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Cloud API must use HTTPS; plain HTTP is restricted to literal loopback hosts");
        }
        return URI.create(value.replaceAll("/+$", ""));
    }

    public static SSLContext trusted(String file) throws Exception {
        if (file == null || file.isBlank()) { return SSLContext.getDefault(); }
        Path path = Path.of(file);
        if (Files.size(path) > 131072) { throw new IllegalArgumentException("Cloud CA certificate file exceeds limit"); }
        try (var input = Files.newInputStream(path)) {
            return certificates(input);
        }
    }

    public static SSLContext trustedPem(String pem) throws Exception {
        if (pem.length() > 131072 || pem.contains("PRIVATE KEY")) {
            throw new IllegalArgumentException("Cloud CA must contain public certificates only");
        }
        try (var input = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))) {
            return certificates(input);
        }
    }

    private static SSLContext certificates(InputStream input) throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        int count = 0;
        for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
            store.setCertificateEntry("ca-" + count++, certificate);
        }
        if (count == 0) { throw new IllegalArgumentException("Cloud CA certificate file is empty"); }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }
}
