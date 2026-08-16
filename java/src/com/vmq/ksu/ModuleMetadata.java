package com.vmq.ksu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

final class ModuleMetadata {
    static final String RUNNING_DESCRIPTION = "V免签通知监听服务正在运行";
    static final String STOPPED_DESCRIPTION = "V免签通知监听服务已停止运行";

    private ModuleMetadata() {
    }

    static boolean updateDescription(Path moduleProperty, boolean running) throws IOException {
        final String description = running ? RUNNING_DESCRIPTION : STOPPED_DESCRIPTION;
        String current = read(moduleProperty);
        if (replaceDescription(current, description).equals(current)) return false;
        AtomicFiles.update(moduleProperty, new AtomicFiles.TextUpdater() {
            @Override public String update(String latest) {
                return replaceDescription(latest, description);
            }
        });
        try {
            Files.setPosixFilePermissions(moduleProperty, PosixFilePermissions.fromString("rw-r--r--"));
        } catch (UnsupportedOperationException ignored) {
            // Windows host tests do not expose POSIX file permissions.
        }
        return true;
    }

    private static String read(Path path) throws IOException {
        if (!Files.exists(path)) throw new IOException("模块属性文件不存在: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String replaceDescription(String current, String description) {
        String replacement = "description=" + description;
        String[] lines = current.split("\\n", -1);
        StringBuilder output = new StringBuilder(current.length() + replacement.length());
        boolean found = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            String normalized = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (normalized.startsWith("description=")) {
                line = replacement + (line.endsWith("\r") ? "\r" : "");
                found = true;
            }
            if (index > 0) output.append('\n');
            output.append(line);
        }
        if (!found) {
            if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
            output.append(replacement).append('\n');
        }
        return output.toString();
    }
}
