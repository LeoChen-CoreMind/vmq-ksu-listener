package com.vmq.ksu;

import java.net.URL;

public final class VmqProtocolTest {
    private VmqProtocolTest() {
    }

    public static void run() throws Exception {
        verifyHeartbeatUrl();
        verifyPaymentUrl();
        verifySecretIsNotSentAndTrailingSlashNormalization();
        verifyPaymentAmountBounds();
    }

    private static void verifyHeartbeatUrl() throws Exception {
        URL url = VmqProtocol.heartbeatUrl("https://vmq.example///", "secret", 1700000000000L);
        require("https://vmq.example/appHeart?t=1700000000000&sign=b21c9cdebed1b892a063e392dd4185b6".equals(url.toString()), "heartbeat URL");
    }

    private static void verifyPaymentUrl() throws Exception {
        PaymentEvent event = new PaymentEvent("event", "com.tencent.mm", "notice", 1L, 1, "1.00", "wechat");
        URL url = VmqProtocol.paymentUrl("https://vmq.example/", "secret", event, 1700000000000L);
        require("https://vmq.example/appPush?type=1&price=1.00&t=1700000000000&sign=5f945170d698251c98d394eabbd9feb9".equals(url.toString()), "payment URL");
    }

    private static void verifySecretIsNotSentAndTrailingSlashNormalization() throws Exception {
        URL url = VmqProtocol.heartbeatUrl("http://vmq.example/base///", "a b&c", 42L);
        require("http://vmq.example/base/appHeart?t=42&sign=0cfac87d59f1f45d93378d1668922727".equals(url.toString()), "secret must only participate in the signature");
    }

    private static void verifyPaymentAmountBounds() throws Exception {
        requireInvalidPrice("0.00");
        requireInvalidPrice("1000000.00");
    }

    private static void requireInvalidPrice(String price) throws Exception {
        PaymentEvent event = new PaymentEvent("event", "com.tencent.mm", "notice", 1L, 1, price, "wechat");
        try {
            VmqProtocol.paymentUrl("https://vmq.example", "secret", event, 1700000000000L);
            throw new AssertionError("payment URL must reject price " + price);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
