package com.vmq.ksu;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class NotificationCursor {
    private final long earliestPostTime;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> latestPostTimes = new LinkedHashMap<String, Long>();

    NotificationCursor(long earliestPostTime, int maxEntries) {
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries 必须为正数");
        this.earliestPostTime = earliestPostTime;
        this.maxEntries = maxEntries;
    }

    synchronized boolean accept(String key, long postTime) {
        if (key == null || key.isEmpty() || postTime < earliestPostTime) return false;
        Long previous = latestPostTimes.get(key);
        if (previous != null && postTime <= previous.longValue()) return false;
        latestPostTimes.put(key, postTime);
        while (latestPostTimes.size() > maxEntries) {
            Iterator<Map.Entry<String, Long>> iterator = latestPostTimes.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    synchronized int size() {
        return latestPostTimes.size();
    }
}
