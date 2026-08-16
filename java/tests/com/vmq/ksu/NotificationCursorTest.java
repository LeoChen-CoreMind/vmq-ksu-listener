package com.vmq.ksu;

public final class NotificationCursorTest {
    private NotificationCursorTest() {
    }

    public static void run() {
        NotificationCursor cursor = new NotificationCursor(1000L, 2);
        require(!cursor.accept("old", 999L), "notifications before the catch-up window must be ignored");
        require(cursor.accept("wechat", 1000L), "first notification must be accepted");
        require(!cursor.accept("wechat", 1000L), "the same notification update must be deduplicated");
        require(cursor.accept("wechat", 1001L), "a newer update for the same key must be accepted");
        require(!cursor.accept("wechat", 1000L), "an out-of-order update must be ignored");
        require(cursor.accept("alipay", 1002L), "a second key must be accepted");
        require(cursor.accept("wework", 1003L), "the cursor must accept after reaching its bound");
        require(cursor.size() == 2, "the cursor must remain bounded");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
