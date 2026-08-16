package com.vmq.ksu;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Delivers listener connection exactly once regardless of callback setup order. */
final class ListenerConnection {
    private final CountDownLatch connectedLatch = new CountDownLatch(1);
    private Runnable callback;
    private boolean connected;

    void markConnected() {
        Runnable callbackToRun;
        synchronized (this) {
            if (connected) {
                return;
            }
            connected = true;
            connectedLatch.countDown();
            callbackToRun = callback;
        }
        if (callbackToRun != null) {
            callbackToRun.run();
        }
    }

    void setCallback(Runnable newCallback) {
        Runnable callbackToRun;
        synchronized (this) {
            callback = newCallback;
            callbackToRun = connected ? newCallback : null;
        }
        if (callbackToRun != null) {
            callbackToRun.run();
        }
    }

    boolean isConnected() {
        synchronized (this) {
            return connected;
        }
    }

    boolean awaitConnected(long timeout, TimeUnit unit) throws InterruptedException {
        return connectedLatch.await(timeout, unit);
    }
}
