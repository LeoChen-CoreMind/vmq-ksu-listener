package com.vmq.ksu;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ListenerHealth {
    private ListenerHealth() {
    }

    public static Map<String, Object> evaluate(String desiredState, boolean supervisorRunning,
            boolean listenerRunning, boolean frameworkConnected, long connectedAt,
            long lastNotificationPollAt, boolean lastNotificationPollSuccess, long lastNotificationAt) {
        boolean stopped = "stopped".equals(desiredState);
        boolean running = "running".equals(desiredState);
        boolean notificationPolling = lastNotificationPollAt > 0L && lastNotificationPollSuccess;
        boolean healthy = (stopped && !supervisorRunning && !listenerRunning && !frameworkConnected)
                || (running && supervisorRunning && listenerRunning && frameworkConnected
                        && notificationPolling);

        LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("healthy", healthy);
        values.put("desiredState", desiredState);
        values.put("supervisorRunning", supervisorRunning);
        values.put("listenerRunning", listenerRunning);
        values.put("frameworkConnected", frameworkConnected);
        values.put("connectedAt", connectedAt);
        values.put("lastNotificationPollAt", lastNotificationPollAt);
        values.put("lastNotificationPollSuccess", lastNotificationPollSuccess);
        values.put("lastNotificationAt", lastNotificationAt);
        values.put("message", message(stopped, running, supervisorRunning, listenerRunning,
                frameworkConnected, lastNotificationPollAt, lastNotificationPollSuccess));
        return values;
    }

    private static String message(boolean stopped, boolean running, boolean supervisorRunning,
            boolean listenerRunning, boolean frameworkConnected, long lastNotificationPollAt,
            boolean lastNotificationPollSuccess) {
        if (stopped && !supervisorRunning && !listenerRunning && !frameworkConnected) return "已停止";
        if (!running && !stopped) return "期望状态无效";
        if (!supervisorRunning) return "服务守护进程未运行";
        if (!listenerRunning) return "监听器未运行";
        if (!frameworkConnected) return "框架未连接";
        if (lastNotificationPollAt <= 0L) return "通知轮询等待中";
        if (!lastNotificationPollSuccess) return "通知轮询失败";
        return "运行中";
    }
}
