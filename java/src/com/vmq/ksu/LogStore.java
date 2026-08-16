package com.vmq.ksu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class LogStore {
    private static final long MAX_BYTES = 256L * 1024L;
    private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    private final Path path;
    private final String secret;

    public LogStore(Path path, String secret) {
        this.path = path;
        this.secret = secret == null ? "" : secret;
        try {
            cleanupExpired();
        } catch (IOException exception) {
            System.err.println("VMQ_LOG_MAINTENANCE_FAILED " + exception.getMessage());
        }
    }

    public synchronized void append(String severity, String eventId, String message) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            String line = timestamp() + " 监听服务 " + severityName(severity) + " " + safe(eventId) + " " + redact(message) + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            cleanupExpired();
            rotateIfNeeded(bytes.length);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            AtomicFiles.secure(path);
        } catch (IOException exception) {
            System.err.println("VMQ_LOG_FAILED " + exception.getMessage());
        }
    }

    private void rotateIfNeeded(int incomingBytes) throws IOException {
        if (!Files.exists(path) || Files.size(path) + incomingBytes <= MAX_BYTES) return;
        Path rotated = path.resolveSibling(path.getFileName() + ".1");
        Files.move(path, rotated, StandardCopyOption.REPLACE_EXISTING);
        AtomicFiles.secure(rotated);
    }

    private void cleanupExpired() throws IOException {
        Path rotated = path.resolveSibling(path.getFileName() + ".1");
        if (Files.exists(rotated) && System.currentTimeMillis() - Files.getLastModifiedTime(rotated).toMillis() > RETENTION_MS) {
            Files.deleteIfExists(rotated);
        }
    }

    public static synchronized void clear(Path active) throws IOException {
        Path rotated = active.resolveSibling(active.getFileName() + ".1");
        Files.deleteIfExists(rotated);
        AtomicFiles.write(active, "");
    }

    private String redact(String value) {
        String safe = safe(value);
        return secret.isEmpty() ? safe : safe.replace(secret, "[REDACTED]");
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String safe(String value) {
        return value == null ? "-" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static String severityName(String severity) {
        if ("info".equals(severity)) return "信息";
        if ("error".equals(severity)) return "错误";
        if ("debug".equals(severity)) return "调试";
        return safe(severity);
    }
}
