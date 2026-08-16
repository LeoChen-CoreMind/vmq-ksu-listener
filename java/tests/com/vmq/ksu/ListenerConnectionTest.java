package com.vmq.ksu;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ListenerConnectionTest {
    private ListenerConnectionTest() {
    }

    public static void main(String[] arguments) throws Exception {
        verifyConnectionBeforeCallback();
        verifyCallbackBeforeDelayedConnection();
        System.out.println("PASS: listener callback handshake ordering");
    }

    private static void verifyConnectionBeforeCallback() {
        ListenerConnection connection = new ListenerConnection();
        AtomicInteger callbacks = new AtomicInteger();
        connection.markConnected();
        connection.setCallback(new Runnable() {
            @Override
            public void run() {
                callbacks.incrementAndGet();
            }
        });
        require(connection.isConnected(), "connection should remain visible before callback setup");
        require(callbacks.get() == 1, "late callback should be delivered exactly once");
    }

    private static void verifyCallbackBeforeDelayedConnection() throws Exception {
        final ListenerConnection connection = new ListenerConnection();
        final AtomicInteger callbacks = new AtomicInteger();
        connection.setCallback(new Runnable() {
            @Override
            public void run() {
                callbacks.incrementAndGet();
            }
        });
        Thread connector = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(100L);
                    connection.markConnected();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        connector.start();
        require(connection.awaitConnected(1L, TimeUnit.SECONDS), "delayed connection should arrive");
        connector.join();
        require(callbacks.get() == 1, "delayed connection should invoke callback exactly once");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
