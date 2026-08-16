package com.vmq.ksu;

public final class PaymentEvent {
    public final String eventId;
    public final String packageName;
    public final String notificationKey;
    public final long postTime;
    public final int type;
    public final String price;
    public final String provider;

    public PaymentEvent(
            String eventId,
            String packageName,
            String notificationKey,
            long postTime,
            int type,
            String price,
            String provider) {
        this.eventId = eventId;
        this.packageName = packageName;
        this.notificationKey = notificationKey;
        this.postTime = postTime;
        this.type = type;
        this.price = price;
        this.provider = provider;
    }
}
