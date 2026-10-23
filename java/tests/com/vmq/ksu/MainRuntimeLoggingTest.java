package com.vmq.ksu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MainRuntimeLoggingTest {
    private MainRuntimeLoggingTest() {
    }

    public static void run() throws Exception {
        verifyDisabledRuntimeMessage();
        verifyParserMissMessage();
        verifyRewardNotificationLogging();
        verifyAmbiguousNotificationLogging();
        verifyAcceptedPaymentMessage();
        verifyProviderNamesForHumanLogs();
        verifyStandardRuntimeMessages();
    }

    private static void verifyDisabledRuntimeMessage() throws Exception {
        Path directory = Files.createTempDirectory("vmq-runtime-disabled-test");
        Object runtime = newRuntime(writeConfig(directory, false, "info"));
        try {
            invoke(runtime, "start");
            String log = readLog(directory);
            require(log.contains("监听服务 信息 - 监听运行时已由配置禁用"), "disabled runtime log localized");
        } finally {
            invoke(runtime, "close");
        }
    }

    private static void verifyParserMissMessage() throws Exception {
        Path directory = Files.createTempDirectory("vmq-runtime-miss-test");
        Object runtime = newRuntime(writeConfig(directory, true, "debug"));
        try {
            notify(runtime, "com.tencent.mm", "miss-key", 1000L, "新消息", "普通通知");
            waitForLog(directory, "未匹配支付通知 package=com.tencent.mm");
        } finally {
            invoke(runtime, "close");
        }
    }

    private static void verifyRewardNotificationLogging() throws Exception {
        Path directory = Files.createTempDirectory("vmq-runtime-reward-test");
        Object runtime = newRuntime(writeConfig(directory, true, "info"));
        try {
            notify(runtime, "com.tencent.mm", "reward-key", 1001L,
                    "微信支付", "[2条]微信支付: 二维码赞赏到账1.00元");
            waitForLog(directory, "已接收支付 来源=微信 金额=1.00");
        } finally {
            invoke(runtime, "close");
        }
    }

    private static void verifyAmbiguousNotificationLogging() throws Exception {
        Path directory = Files.createTempDirectory("vmq-runtime-ambiguous-test");
        Object runtime = newRuntime(writeConfig(directory, true, "info"));
        String notification = "二维码赞赏到账1.00元\n二维码赞赏到账2.00元";
        try {
            notify(runtime, "com.tencent.mm", "ambiguous-key", 1002L, "微信支付", notification);
            String log = waitForLog(directory, "通知包含多个不同金额，未自动入账 package=com.tencent.mm");
            require(!log.contains("已接收支付"), "ambiguous notification must not be accepted");
            require(!log.contains(notification), "ambiguous log must not include notification contents");
        } finally {
            invoke(runtime, "close");
        }
    }

    private static void verifyAcceptedPaymentMessage() throws Exception {
        PaymentEvent known = new PaymentEvent("event-1", "com.tencent.mm", "key", 1001L, 1, "1.23", "wechat");
        PaymentEvent unknown = new PaymentEvent("event-2", "com.example", "key", 1002L, 1, "2.34", "bank-test");
        require("已接收支付 来源=微信 金额=1.23".equals(RuntimeLogMessages.paymentAccepted(known)),
                "accepted payment message localizes known provider");
        require("已接收支付 来源=bank-test 金额=2.34".equals(RuntimeLogMessages.paymentAccepted(unknown)),
                "accepted payment message keeps unknown provider printable");
    }

    private static void verifyProviderNamesForHumanLogs() {
        require("微信".equals(RuntimeLogMessages.providerName("wechat")), "wechat provider localized");
        require("企业微信".equals(RuntimeLogMessages.providerName("wecom")), "wecom provider localized");
        require("支付宝".equals(RuntimeLogMessages.providerName("alipay")), "alipay provider localized");
        require("bank-test".equals(RuntimeLogMessages.providerName("bank-test")), "unknown provider remains printable");
        require("-".equals(RuntimeLogMessages.providerName(null)), "null provider remains printable");
    }

    private static void verifyStandardRuntimeMessages() {
        require("监听运行时已由配置禁用".equals(RuntimeLogMessages.runtimeDisabled()), "disabled runtime message");
        require("未匹配支付通知 package=com.tencent.mm".equals(RuntimeLogMessages.parserMiss("com.tencent.mm")), "parser miss message");
        require("入队失败: java.lang.Exception: boom".equals(RuntimeLogMessages.enqueueFailed("java.lang.Exception: boom")), "enqueue failure message");
        require("心跳: 成功".equals(RuntimeLogMessages.heartbeat("成功")), "heartbeat message");
        require("支付回调成功".equals(RuntimeLogMessages.paymentDelivered()), "delivery success message");
        require("支付回调失败: 服务器拒绝响应".equals(RuntimeLogMessages.paymentDeliveryFailed("服务器拒绝响应")), "delivery failure message");
        require("投递工作线程失败: java.lang.Exception: boom".equals(RuntimeLogMessages.deliveryWorkerFailed("java.lang.Exception: boom")), "worker failure message");
        require("状态更新失败: java.lang.Exception: boom".equals(RuntimeLogMessages.stateUpdateFailed("java.lang.Exception: boom")), "state update failure message");
    }

    private static Object newRuntime(Path configPath) throws Exception {
        Class<?> runtimeType = Class.forName("com.vmq.ksu.Main$ListenerRuntime");
        Constructor<?> constructor = runtimeType.getDeclaredConstructor(Path.class);
        constructor.setAccessible(true);
        return constructor.newInstance(configPath);
    }

    private static void notify(Object runtime, String packageName, String key, long postTime, String title, String text)
            throws Exception {
        Method method = runtime.getClass().getDeclaredMethod("onNotification", String.class, String.class, long.class,
                String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(runtime, packageName, key, postTime, title, text, null, null);
    }

    private static void invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static Path writeConfig(Path directory, boolean enabled, String logLevel) throws Exception {
        Path path = directory.resolve("config.json");
        String json = "{\"baseUrl\":\"http://127.0.0.1:9\",\"key\":\"secret-value\",\"enabled\":" + enabled + ","
                + "\"wechatEnabled\":true,\"wecomEnabled\":true,\"alipayEnabled\":true,"
                + "\"heartbeatSeconds\":50,\"connectTimeoutSeconds\":1,\"readTimeoutSeconds\":1,"
                + "\"maxRetries\":8,\"logLevel\":\"" + logLevel + "\"}";
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static String waitForLog(Path directory, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = readLog(directory);
            if (text.contains(expected)) return text;
            Thread.sleep(25L);
        }
        throw new AssertionError("missing log text: " + expected + "\n" + text);
    }

    private static String readLog(Path directory) throws Exception {
        Path path = directory.resolve("vmq.log");
        if (!Files.exists(path)) return "";
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
