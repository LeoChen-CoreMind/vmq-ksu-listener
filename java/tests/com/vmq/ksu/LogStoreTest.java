package com.vmq.ksu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;

public final class LogStoreTest {
    private LogStoreTest() {
    }

    public static void run() throws Exception {
        verifyChineseAppendAndHistoricalLogsUntouched();

        Path directory = Files.createTempDirectory("vmq-log-test");
        Path active = directory.resolve("vmq.log");
        Path rotated = directory.resolve("vmq.log.1");
        StringBuilder old = new StringBuilder();
        for (int index = 0; index < 80; index++) old.append("old-").append(index).append('\n');
        StringBuilder newer = new StringBuilder();
        for (int index = 0; index < 250; index++) newer.append("line-").append(index).append('\n');
        newer.append("secret-value\n");
        Files.write(rotated, old.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(active, newer.toString().getBytes(StandardCharsets.UTF_8));

        LogTail.Result result = LogTail.read(active, rotated, 200, 32 * 1024);
        require(result.lineCount <= 200, "line cap");
        require(result.text.getBytes(StandardCharsets.UTF_8).length <= 32 * 1024, "byte cap");
        require(result.text.contains("line-249"), "newest line retained");
        require(result.truncated, "tail reports truncation");

        StringBuilder longLines = new StringBuilder();
        for (int index = 0; index < 600; index++) longLines.append("long-").append(index).append('-').append(repeat('x', 120)).append('\n');
        Files.write(active, longLines.toString().getBytes(StandardCharsets.UTF_8));
        LogTail.Result longResult = LogTail.read(active, rotated, 200, 32 * 1024);
        require(longResult.text.contains("long-599"), "long tail keeps newest lines");
        require(!longResult.text.contains("long-0-"), "long tail drops oldest lines");

        LogStore store = new LogStore(active, "secret-value");
        for (int index = 0; index < 5000; index++) store.append("info", UUID.randomUUID().toString(), "payload-" + index);
        require(Files.exists(rotated), "rotation creates .1");
        require(Files.size(active) <= 256L * 1024L, "active cap");
        require(Files.size(rotated) <= 256L * 1024L, "rotated cap");
        require(!new String(Files.readAllBytes(active), StandardCharsets.UTF_8).contains("secret-value"), "secret redacted");

        Files.setLastModifiedTime(rotated, FileTime.fromMillis(System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L));
        new LogStore(active, "secret-value");
        require(!Files.exists(rotated), "old rotation removed");
        Files.write(rotated, "old\n".getBytes(StandardCharsets.UTF_8));
        LogStore.clear(active);
        require(!Files.exists(rotated), "clear removes rotation");
        require(Files.size(active) == 0L, "clear empties active log");
    }

    private static void verifyChineseAppendAndHistoricalLogsUntouched() throws Exception {
        Path directory = Files.createTempDirectory("vmq-log-format-test");
        Path active = directory.resolve("vmq.log");
        Files.write(active, "2026-01-01T00:00:00.000Z listener info old-event old english log\n".getBytes(StandardCharsets.UTF_8));

        LogStore store = new LogStore(active, "secret-value");
        store.append("info", "event-info", "first secret-value");
        store.append("error", "event-error", "second");
        store.append("debug", "event-debug", "third");

        String text = new String(Files.readAllBytes(active), StandardCharsets.UTF_8);
        require(text.contains("listener info old-event old english log"), "historical log remains untouched");
        require(text.contains("监听服务 信息 event-info first [REDACTED]"), "info severity localized and secret redacted");
        require(text.contains("监听服务 错误 event-error second"), "error severity localized");
        require(text.contains("监听服务 调试 event-debug third"), "debug severity localized");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
