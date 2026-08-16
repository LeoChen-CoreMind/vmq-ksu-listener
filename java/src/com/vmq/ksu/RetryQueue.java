package com.vmq.ksu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RetryQueue {
    private static final int[] RETRY_DELAYS_SECONDS = {1, 2, 5, 15, 30};
    private static final long DEDUP_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_DEDUP_IDS = 512;

    public static final class Entry {
        public final PaymentEvent event;
        public final int attempts;
        public final long nextAttemptAt;
        public final String status;
        public final String lastError;

        Entry(PaymentEvent event, int attempts, long nextAttemptAt, String status, String lastError) {
            this.event = event;
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.status = status;
            this.lastError = lastError;
        }
    }

    private final Path queuePath;
    private final Path dedupPath;
    private final int maxRetries;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final LinkedHashMap<String, Long> delivered = new LinkedHashMap<String, Long>();

    public RetryQueue(Path queuePath, Path dedupPath, int maxRetries) throws IOException {
        this.queuePath = queuePath;
        this.dedupPath = dedupPath;
        this.maxRetries = maxRetries;
        load();
    }

    public synchronized boolean enqueue(PaymentEvent event) throws IOException {
        if (event == null) throw new IllegalArgumentException("event 不能为空");
        pruneDelivered(System.currentTimeMillis());
        if (delivered.containsKey(event.eventId) || find(event.eventId) >= 0) return false;
        entries.add(new Entry(event, 0, 0L, "pending", ""));
        persistQueue();
        return true;
    }

    public synchronized Optional<Entry> peekDue(long nowMs) {
        for (Entry entry : entries) {
            if (("pending".equals(entry.status) || "retry".equals(entry.status)) && entry.nextAttemptAt <= nowMs) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public synchronized void markDelivered(String eventId) throws IOException {
        int index = find(eventId);
        if (index < 0) return;
        delivered.put(eventId, System.currentTimeMillis());
        pruneDelivered(System.currentTimeMillis());
        persistDedup();
        entries.remove(index);
        persistQueue();
    }

    public synchronized void markFailed(String eventId, String error, long nowMs) throws IOException {
        int index = find(eventId);
        if (index < 0) return;
        Entry current = entries.get(index);
        int attempts = current.attempts + 1;
        if (attempts > maxRetries) {
            entries.set(index, new Entry(current.event, attempts, current.nextAttemptAt, "failed", safe(error)));
        } else {
            int delayIndex = Math.min(attempts - 1, RETRY_DELAYS_SECONDS.length - 1);
            long nextAttemptAt = nowMs + RETRY_DELAYS_SECONDS[delayIndex] * 1000L;
            entries.set(index, new Entry(current.event, attempts, nextAttemptAt, "retry", safe(error)));
        }
        persistQueue();
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public synchronized int pendingCount() {
        int count = 0;
        for (Entry entry : entries) if (!"failed".equals(entry.status)) count++;
        return count;
    }

    public synchronized int failedCount() {
        int count = 0;
        for (Entry entry : entries) if ("failed".equals(entry.status)) count++;
        return count;
    }

    private void load() throws IOException {
        entries.clear();
        if (Files.exists(queuePath)) {
            for (String line : Files.readAllLines(queuePath, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) entries.add(entryFromMap(JsonCodec.parseObject(line)));
            }
        }
        delivered.clear();
        if (Files.exists(dedupPath)) {
            Map<String, Object> root = JsonCodec.parseObject(new String(Files.readAllBytes(dedupPath), StandardCharsets.UTF_8));
            Object rawEvents = root.get("events");
            if (rawEvents instanceof List) {
                for (Object rawEvent : (List<?>) rawEvents) {
                    if (!(rawEvent instanceof Map)) continue;
                    Map<?, ?> event = (Map<?, ?>) rawEvent;
                    Object id = event.get("id");
                    Object time = event.get("time");
                    if (id instanceof String && time instanceof Number) delivered.put((String) id, ((Number) time).longValue());
                }
            }
        }
        pruneDelivered(System.currentTimeMillis());
        Iterator<Entry> queueIterator = entries.iterator();
        while (queueIterator.hasNext()) {
            if (delivered.containsKey(queueIterator.next().event.eventId)) queueIterator.remove();
        }
    }

    private int find(String eventId) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).event.eventId.equals(eventId)) return index;
        }
        return -1;
    }

    private void pruneDelivered(long nowMs) {
        Iterator<Map.Entry<String, Long>> iterator = delivered.entrySet().iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().getValue() > DEDUP_TTL_MS) iterator.remove();
        }
        while (delivered.size() > MAX_DEDUP_IDS) {
            Iterator<String> ids = delivered.keySet().iterator();
            ids.next();
            ids.remove();
        }
    }

    private void persistQueue() throws IOException {
        StringBuilder output = new StringBuilder();
        for (Entry entry : entries) output.append(JsonCodec.stringify(entryToMap(entry))).append('\n');
        AtomicFiles.write(queuePath, output.toString());
    }

    private void persistDedup() throws IOException {
        List<Object> events = new ArrayList<Object>();
        for (Map.Entry<String, Long> item : delivered.entrySet()) {
            LinkedHashMap<String, Object> event = new LinkedHashMap<String, Object>();
            event.put("id", item.getKey());
            event.put("time", item.getValue());
            events.add(event);
        }
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("events", events);
        AtomicFiles.write(dedupPath, JsonCodec.stringify(root) + "\n");
    }

    private static Map<String, Object> entryToMap(Entry entry) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("event", eventToMap(entry.event));
        values.put("attempts", entry.attempts);
        values.put("nextAttemptAt", entry.nextAttemptAt);
        values.put("status", entry.status);
        values.put("lastError", entry.lastError);
        return values;
    }

    private static Map<String, Object> eventToMap(PaymentEvent event) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("eventId", event.eventId);
        values.put("packageName", event.packageName);
        values.put("notificationKey", event.notificationKey);
        values.put("postTime", event.postTime);
        values.put("type", event.type);
        values.put("price", event.price);
        values.put("provider", event.provider);
        return values;
    }

    private static Entry entryFromMap(Map<String, Object> values) {
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) values.get("event");
        PaymentEvent payment = new PaymentEvent(
                string(event, "eventId"), string(event, "packageName"), string(event, "notificationKey"),
                number(event, "postTime").longValue(), number(event, "type").intValue(),
                string(event, "price"), string(event, "provider"));
        return new Entry(payment, number(values, "attempts").intValue(),
                number(values, "nextAttemptAt").longValue(), string(values, "status"), string(values, "lastError"));
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("队列字段 " + key + " 必须是字符串");
        return (String) value;
    }

    private static Number number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException("队列字段 " + key + " 必须是数字");
        return (Number) value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
