package com.vmq.ksu;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class RetryQueueTest {
    private RetryQueueTest() {
    }

    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("vmq-queue-test");
        Path queuePath = directory.resolve("queue.jsonl");
        Path dedupPath = directory.resolve("dedup.json");
        RetryQueue queue = new RetryQueue(queuePath, dedupPath, 8);
        PaymentEvent first = event("event-1", "1.00");
        PaymentEvent second = event("event-2", "1.00");

        require(queue.enqueue(first), "first event must be inserted");
        require(!queue.enqueue(first), "duplicate pending event must be ignored");
        require(queue.enqueue(second), "second event must be inserted");
        require(queue.entries().size() == 2, "deduplicate only by event ID");

        queue = new RetryQueue(queuePath, dedupPath, 8);
        require(queue.entries().size() == 2, "queue must survive reload");

        long now = 100000L;
        int[] delays = {1, 2, 5, 15, 30, 30};
        for (int delay : delays) {
            queue.markFailed(first.eventId, "offline", now);
            RetryQueue.Entry entry = find(queue.entries(), first.eventId);
            require(entry.nextAttemptAt == now + delay * 1000L, "retry delay " + delay);
            now = entry.nextAttemptAt;
        }

        RetryQueue exhausted = new RetryQueue(directory.resolve("exhausted.jsonl"), directory.resolve("exhausted-dedup.json"), 1);
        exhausted.enqueue(first);
        exhausted.markFailed(first.eventId, "offline", 1L);
        exhausted.markFailed(first.eventId, "offline", 2L);
        require("failed".equals(exhausted.entries().get(0).status), "exhausted events remain failed");

        queue.markDelivered(second.eventId);
        queue = new RetryQueue(queuePath, dedupPath, 8);
        require(!queue.enqueue(second), "delivered event must be ignored");
        require(findOrNull(queue.entries(), second.eventId) == null, "delivered event ID remains deduplicated after reload");
    }

    private static RetryQueue.Entry find(List<RetryQueue.Entry> entries, String eventId) {
        RetryQueue.Entry entry = findOrNull(entries, eventId);
        if (entry != null) return entry;
        throw new AssertionError("missing event " + eventId);
    }

    private static RetryQueue.Entry findOrNull(List<RetryQueue.Entry> entries, String eventId) {
        for (RetryQueue.Entry entry : entries) {
            if (eventId.equals(entry.event.eventId)) {
                return entry;
            }
        }
        return null;
    }

    private static PaymentEvent event(String id, String price) {
        return new PaymentEvent(id, "com.tencent.mm", "key-" + id, 1L, 1, price, "wechat");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
