package com.vmq.ksu;

public final class TestMain {
    private TestMain() {
    }

    public static void main(String[] arguments) throws Exception {
        ListenerConnectionTest.main(arguments);
        NotificationCursorTest.run();
        PaymentParserTest.run();
        VmqProtocolTest.run();
        ConfigTest.run();
        ListenerHealthTest.run();
        RetryQueueTest.run();
        VmqClientTest.run();
        LogStoreTest.run();
        ModuleMetadataTest.run();
        MainRuntimeLoggingTest.run();
        java.nio.file.Path configPath = java.nio.file.Files.createTempFile("vmq-state-config", ".json");
        java.nio.file.Files.write(configPath, ("{\"baseUrl\":\"https://vmq.example\",\"key\":\"secret\","
                + "\"enabled\":true,\"wechatEnabled\":true,\"wecomEnabled\":true,\"alipayEnabled\":true,"
                + "\"heartbeatSeconds\":50,\"connectTimeoutSeconds\":10,\"readTimeoutSeconds\":30,"
                + "\"maxRetries\":8,\"logLevel\":\"info\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VmqClientTest.verifyRuntimeState(Config.load(configPath));
        System.out.println("PASS: payment parser, VMQ protocol, config, retry queue, transport, and state");
    }
}
