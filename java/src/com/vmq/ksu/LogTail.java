package com.vmq.ksu;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads only a bounded tail of the active and rotated log files. */
public final class LogTail {
    private static final int WINDOW_PADDING = 4096;

    private LogTail() {
    }

    public static Result read(Path active, Path rotated, int maxLines, int maxBytes) throws IOException {
        if (maxLines < 1 || maxBytes < 1) throw new IllegalArgumentException("日志读取限制必须为正数");
        String older = readWindow(rotated, maxBytes + WINDOW_PADDING);
        String newer = readWindow(active, maxBytes + WINDOW_PADDING);
        boolean sourceTruncated = isTruncated(rotated, maxBytes + WINDOW_PADDING)
                || isTruncated(active, maxBytes + WINDOW_PADDING);

        List<String> lines = new ArrayList<String>();
        lines.addAll(lines(older));
        lines.addAll(lines(newer));
        boolean truncated = sourceTruncated || lines.size() > maxLines;
        while (lines.size() > maxLines) lines.remove(0);

        int first = 0;
        int totalBytes = 0;
        for (String line : lines) totalBytes += (line + "\n").getBytes(StandardCharsets.UTF_8).length;
        while (first < lines.size() && totalBytes > maxBytes) {
            totalBytes -= (lines.get(first) + "\n").getBytes(StandardCharsets.UTF_8).length;
            first++;
            truncated = true;
        }
        StringBuilder text = new StringBuilder(totalBytes);
        for (int index = first; index < lines.size(); index++) text.append(lines.get(index)).append('\n');
        return new Result(text.toString(), countLines(text), truncated);
    }

    private static String readWindow(Path path, int window) throws IOException {
        if (!Files.exists(path)) return "";
        long length = Files.size(path);
        int count = (int) Math.min(length, window);
        long offset = length - count;
        byte[] bytes = new byte[count];
        RandomAccessFile file = new RandomAccessFile(path.toFile(), "r");
        try {
            file.seek(offset);
            file.readFully(bytes);
        } finally {
            file.close();
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (offset > 0) {
            int newline = value.indexOf('\n');
            return newline < 0 ? "" : value.substring(newline + 1);
        }
        return value;
    }

    private static boolean isTruncated(Path path, int window) throws IOException {
        return Files.exists(path) && Files.size(path) > window;
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return new ArrayList<String>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> result = new ArrayList<String>(Arrays.asList(normalized.split("\n", -1)));
        if (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return result;
    }

    private static int countLines(CharSequence value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == '\n') count++;
        return count;
    }

    public static final class Result {
        public final String text;
        public final int lineCount;
        public final boolean truncated;

        Result(String text, int lineCount, boolean truncated) {
            this.text = text;
            this.lineCount = lineCount;
            this.truncated = truncated;
        }
    }
}
