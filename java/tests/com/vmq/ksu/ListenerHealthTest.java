package com.vmq.ksu;

import java.util.Map;

public final class ListenerHealthTest {
    private ListenerHealthTest() {
    }

    public static void run() {
        Map<String, Object> stopped = ListenerHealth.evaluate(
                "stopped", false, false, false, 0L, 0L, false, 0L);
        require(Boolean.TRUE.equals(stopped.get("healthy")), "stopped listener must be healthy");
        require("已停止".equals(stopped.get("message")), "stopped listener message");

        Map<String, Object> processOnly = ListenerHealth.evaluate(
                "running", true, true, false, 0L, 0L, false, 0L);
        require(!Boolean.TRUE.equals(processOnly.get("healthy")), "process-only listener must be unhealthy");

        Map<String, Object> pollFailed = ListenerHealth.evaluate(
                "running", true, true, true, 100L, 300L, false, 200L);
        require(!Boolean.TRUE.equals(pollFailed.get("healthy")), "failed notification polling must be unhealthy");
        require("通知轮询失败".equals(pollFailed.get("message")), "failed poll message");

        Map<String, Object> pollPending = ListenerHealth.evaluate(
                "running", true, true, true, 100L, 0L, false, 0L);
        require(!Boolean.TRUE.equals(pollPending.get("healthy")), "pending notification polling must be unhealthy");
        require("通知轮询等待中".equals(pollPending.get("message")), "pending poll message");

        Map<String, Object> connected = ListenerHealth.evaluate(
                "running", true, true, true, 100L, 300L, true, 200L);
        require(Boolean.TRUE.equals(connected.get("healthy")), "connected listener must be healthy");
        require("运行中".equals(connected.get("message")), "connected listener message");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
