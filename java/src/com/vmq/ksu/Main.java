package com.vmq.ksu;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) {
        if (arguments.length > 0 && "--once-heartbeat".equals(arguments[0])) {
            System.exit(runOnceHeartbeat(arguments));
            return;
        }

        boolean daemon = arguments.length > 0
                && ("--daemon".equals(arguments[0]) || "--root".equals(arguments[0]));
        Path configPath = daemon && arguments.length > 1
                ? ("--root".equals(arguments[0])
                        ? Paths.get(arguments[1]).resolve("config.json") : Paths.get(arguments[1]))
                : Paths.get("/data/adb/vmq_ksu/config.json");
        final VmqNotificationListener listener = new VmqNotificationListener();
        try {
            prepareMainLooper();
            boolean notificationParcelingPrepared = FrameworkBridge.prepareNotificationParceling();
            System.out.println("VMQ_FRAMEWORK_FLAGS_READY systemuiCached=" + notificationParcelingPrepared);
            System.out.flush();
            Context context = systemContext();
            final ComponentName component = FrameworkBridge.register(listener, context, 0);
            final ListenerRuntime runtime = daemon ? new ListenerRuntime(configPath) : null;
            if (runtime != null) {
                listener.setNotificationCallback(runtime);
                runtime.start();
            }
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    if (runtime != null) runtime.close();
                    listener.close();
                    try {
                        FrameworkBridge.unregister(listener);
                    } catch (Exception ignored) {
                        // The process is terminating; there is no recovery path for this cleanup.
                    }
                }
            }, "vmq-listener-unregister"));

            listener.setConnectionCallback(new Runnable() {
                @Override
                public void run() {
                    if (runtime != null) runtime.connected();
                    System.out.println("VMQ_REGISTERED component=" + component.flattenToShortString());
                    System.out.flush();
                }
            });
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!listener.isConnected()) {
                        System.err.println("VMQ_REGISTER_FAILED java.lang.IllegalStateException: "
                                + "onListenerConnected timed out after 5000ms");
                        System.err.flush();
                        System.exit(1);
                    }
                }
            }, 5000L);
            Looper.loop();
        } catch (Throwable exception) {
            System.err.println("VMQ_REGISTER_FAILED " + describe(exception));
            System.err.flush();
            System.exit(1);
        }
    }

    private static int runOnceHeartbeat(String[] arguments) {
        LinkedHashMap<String, Object> output = new LinkedHashMap<String, Object>();
        try {
            if (arguments.length != 2) throw new IllegalArgumentException("用法：--once-heartbeat <config.json>");
            Config config = Config.load(Paths.get(arguments[1]));
            VmqClient.DeliveryResult result = VmqClient.sendHeartbeat(config);
            output.putAll(result.toMap());
            System.out.println(JsonCodec.stringify(output));
            return result.success ? 0 : 1;
        } catch (Exception exception) {
            output.put("success", false);
            output.put("error", describe(exception));
            System.out.println(JsonCodec.stringify(output));
            return 1;
        }
    }

    private static void prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
    }

    private static Context systemContext() throws Exception {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method systemMain = activityThread.getDeclaredMethod("systemMain");
            Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
            systemMain.setAccessible(true);
            getSystemContext.setAccessible(true);
            Object thread = systemMain.invoke(null);
            final Context base = (Context) getSystemContext.invoke(thread);
            final ApplicationInfo applicationInfo = new ApplicationInfo(base.getApplicationInfo());
            applicationInfo.targetSdkVersion = Build.VERSION.SDK_INT;
            return new ContextWrapper(base) {
                @Override
                public ApplicationInfo getApplicationInfo() {
                    return applicationInfo;
                }
            };
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw exception;
        }
    }

    private static String describe(Throwable exception) {
        String message = exception.getMessage();
        return exception.getClass().getName() + ": " + (message == null ? "" : message);
    }

    private static final class ListenerRuntime implements VmqNotificationListener.NotificationCallback {
        private final Config config;
        private final RetryQueue queue;
        private final RuntimeState state;
        private final LogStore logs;
        private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService delivery = Executors.newSingleThreadScheduledExecutor();

        ListenerRuntime(Path configPath) throws Exception {
            config = Config.load(configPath);
            Path dataDirectory = configPath.toAbsolutePath().getParent();
            if (dataDirectory == null) throw new IllegalArgumentException("config 路径必须包含父目录");
            queue = new RetryQueue(dataDirectory.resolve("queue.jsonl"), dataDirectory.resolve("dedup.json"), config.maxRetries);
            state = new RuntimeState(dataDirectory.resolve("state.json"));
            logs = new LogStore(dataDirectory.resolve("vmq.log"), config.key);
        }

        void start() {
            updateState(values("running", true, "connected", false, "connectedAt", 0L,
                    "lastNotificationPollAt", 0L, "activeProviderNotifications", 0,
                    "lastNotificationPollSuccess", false, "lastNotificationPollMessage", "pending",
                    "pending", queue.pendingCount(), "failed", queue.failedCount()));
            if (!config.enabled) {
                logs.append("info", "-", RuntimeLogMessages.runtimeDisabled());
                return;
            }
            heartbeat.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { sendHeartbeat(); }
            }, 0L, config.heartbeatSeconds, TimeUnit.SECONDS);
            delivery.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { deliverNext(); }
            }, 0L, 500L, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onNotification(final String packageName, final String key, final long postTime,
                final String title, final String text, final String bigText, final String subText) {
            updateState(values("lastNotificationAt", System.currentTimeMillis(),
                    "lastNotificationPackage", packageName));
            if (!config.enabled || !providerEnabled(packageName)) return;
            delivery.execute(new Runnable() {
                @Override public void run() {
                    try {
                        Optional<PaymentEvent> parsed = PaymentParser.parse(packageName, key, postTime, title, text, bigText, subText);
                        if (!parsed.isPresent()) {
                            if (PaymentParser.ambiguousAmounts(title, text, bigText, subText)) {
                                logs.append("info", "-", "通知包含多个不同金额，未自动入账 package=" + packageName);
                            }
                            if ("debug".equals(config.logLevel)) {
                                logs.append("debug", "-", RuntimeLogMessages.parserMiss(packageName));
                                updateState(values("lastParserMissAt", System.currentTimeMillis(),
                                        "lastParserMissPackage", packageName));
                            }
                            return;
                        }
                        PaymentEvent event = parsed.get();
                        if (!queue.enqueue(event)) return;
                        logs.append("info", event.eventId, RuntimeLogMessages.paymentAccepted(event));
                        updateState(values("lastAcceptedEvent", event.eventId, "pending", queue.pendingCount(), "failed", queue.failedCount()));
                    } catch (Exception exception) {
                        logs.append("error", "-", RuntimeLogMessages.enqueueFailed(describe(exception)));
                        updateState(values("lastError", describe(exception)));
                    }
                }
            });
        }

        @Override
        public void onNotificationPoll(int activeProviderNotifications, String error) {
            updateState(values("lastNotificationPollAt", System.currentTimeMillis(),
                    "activeProviderNotifications", activeProviderNotifications,
                    "lastNotificationPollSuccess", error == null,
                    "lastNotificationPollMessage", error == null ? "success" : error));
        }

        void close() {
            heartbeat.shutdownNow();
            delivery.shutdownNow();
            updateState(values("running", false, "connected", false));
        }

        void connected() {
            updateState(values("connected", true, "connectedAt", System.currentTimeMillis()));
        }

        private void sendHeartbeat() {
            VmqClient.DeliveryResult result = VmqClient.sendHeartbeat(config);
            logs.append(result.success ? "info" : "error", "-", RuntimeLogMessages.heartbeat(result.message));
            updateState(values("lastHeartbeatAt", System.currentTimeMillis(), "lastHeartbeatSuccess", result.success,
                    "lastHeartbeatMessage", result.message));
        }

        private void deliverNext() {
            try {
                Optional<RetryQueue.Entry> due = queue.peekDue(System.currentTimeMillis());
                if (!due.isPresent()) return;
                RetryQueue.Entry entry = due.get();
                VmqClient.DeliveryResult result = VmqClient.sendPayment(config, entry.event);
                if (result.success) {
                    queue.markDelivered(entry.event.eventId);
                    logs.append("info", entry.event.eventId, RuntimeLogMessages.paymentDelivered());
                } else {
                    queue.markFailed(entry.event.eventId, result.message, System.currentTimeMillis());
                    logs.append("error", entry.event.eventId, RuntimeLogMessages.paymentDeliveryFailed(result.message));
                }
                updateState(values("lastDeliveryEvent", entry.event.eventId, "lastDeliverySuccess", result.success,
                        "lastDeliveryMessage", result.message, "pending", queue.pendingCount(), "failed", queue.failedCount()));
            } catch (Exception exception) {
                logs.append("error", "-", RuntimeLogMessages.deliveryWorkerFailed(describe(exception)));
                updateState(values("lastError", describe(exception)));
            }
        }

        private boolean providerEnabled(String packageName) {
            if ("com.tencent.mm".equals(packageName)) return config.wechatEnabled;
            if ("com.tencent.wework".equals(packageName)) return config.wecomEnabled;
            if ("com.eg.android.AlipayGphone".equals(packageName)) return config.alipayEnabled;
            return false;
        }

        private void updateState(Map<String, Object> values) {
            try {
                state.mergeListener(values);
            } catch (Exception exception) {
                logs.append("error", "-", RuntimeLogMessages.stateUpdateFailed(describe(exception)));
            }
        }

        private static Map<String, Object> values(Object... pairs) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
            for (int index = 0; index < pairs.length; index += 2) values.put((String) pairs[index], pairs[index + 1]);
            return values;
        }
    }
}
