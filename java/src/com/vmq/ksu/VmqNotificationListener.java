package com.vmq.ksu;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VmqNotificationListener extends NotificationListenerService {
    private static final long CATCH_UP_WINDOW_MS = TimeUnit.MINUTES.toMillis(30L);
    private static final long POLL_INTERVAL_MS = 1000L;
    private static final long POLL_ERROR_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1L);

    public interface NotificationCallback {
        void onNotification(String packageName, String key, long postTime,
                String title, String text, String bigText, String subText);
        void onNotificationPoll(int activeProviderNotifications, String error);
    }

    private final ListenerConnection connection = new ListenerConnection();
    private final NotificationCursor cursor = new NotificationCursor(
            System.currentTimeMillis() - CATCH_UP_WINDOW_MS, 512);
    private final ScheduledExecutorService notificationPoller = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean pollingStarted = new AtomicBoolean();
    private volatile NotificationCallback notificationCallback;
    private volatile long lastPollErrorAt;
    private volatile long lastPollReportAt;

    @Override
    public void onListenerConnected() {
        connection.markConnected();
        startPolling();
    }

    public void setConnectionCallback(Runnable callback) {
        connection.setCallback(callback);
    }

    public void setNotificationCallback(NotificationCallback callback) {
        notificationCallback = callback;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        dispatch(notification);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification,
            NotificationListenerService.RankingMap rankingMap) {
        dispatch(notification);
    }

    public void close() {
        notificationPoller.shutdownNow();
    }

    private boolean dispatch(StatusBarNotification notification) {
        NotificationCallback callback = notificationCallback;
        if (callback == null || notification == null || notification.getNotification() == null
                || !providerPackage(notification.getPackageName())
                || !cursor.accept(notification.getKey(), notification.getPostTime())) return false;
        Notification androidNotification = notification.getNotification();
        Bundle extras = androidNotification.extras;
        callback.onNotification(notification.getPackageName(), notification.getKey(), notification.getPostTime(),
                text(extras, Notification.EXTRA_TITLE), text(extras, Notification.EXTRA_TEXT),
                text(extras, Notification.EXTRA_BIG_TEXT), text(extras, Notification.EXTRA_SUB_TEXT));
        return true;
    }

    public boolean isConnected() {
        return connection.isConnected();
    }

    public boolean awaitConnected(long timeout, TimeUnit unit) throws InterruptedException {
        return connection.awaitConnected(timeout, unit);
    }

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) return;
        notificationPoller.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    StatusBarNotification[] notifications = getActiveNotifications();
                    if (notifications == null) {
                        reportPollError("getActiveNotifications returned null");
                        return;
                    }
                    int providerCount = 0;
                    boolean dispatched = false;
                    for (StatusBarNotification notification : notifications) {
                        if (notification != null && providerPackage(notification.getPackageName())) providerCount++;
                        dispatched |= dispatch(notification);
                    }
                    long now = System.currentTimeMillis();
                    if (dispatched || now - lastPollReportAt >= 10000L) {
                        lastPollReportAt = now;
                        NotificationCallback callback = notificationCallback;
                        if (callback != null) callback.onNotificationPoll(providerCount, null);
                    }
                } catch (Throwable exception) {
                    reportPollError(exception);
                }
            }
        }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void reportPollError(Throwable exception) {
        reportPollError(exception.getClass().getName() + ": " + String.valueOf(exception.getMessage()), exception);
    }

    private void reportPollError(String message) {
        reportPollError(message, null);
    }

    private void reportPollError(String message, Throwable exception) {
        long now = System.currentTimeMillis();
        if (now - lastPollErrorAt < POLL_ERROR_INTERVAL_MS) return;
        lastPollErrorAt = now;
        NotificationCallback callback = notificationCallback;
        if (callback != null) {
            callback.onNotificationPoll(-1, message);
        }
        System.err.println("VMQ_NOTIFICATION_POLL_FAILED " + message);
        if (exception != null) exception.printStackTrace(System.err);
        System.err.flush();
    }

    private static boolean providerPackage(String packageName) {
        return "com.tencent.mm".equals(packageName)
                || "com.tencent.wework".equals(packageName)
                || "com.eg.android.AlipayGphone".equals(packageName);
    }

    private static String text(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }
}
