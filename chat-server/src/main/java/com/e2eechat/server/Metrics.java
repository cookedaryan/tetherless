package com.e2eechat.server;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Metrics {
    public static final AtomicInteger connectedClients = new AtomicInteger(0);
    public static final AtomicLong messagesRouted = new AtomicLong(0);
    public static final AtomicInteger queueHighWaterMark = new AtomicInteger(0);
    
    public static final AtomicLong rejectedServerFull = new AtomicLong(0);
    public static final AtomicLong rejectedTooManyConnections = new AtomicLong(0);
    public static final AtomicLong rejectedRateLimit = new AtomicLong(0);
    public static final AtomicLong rejectedBufferOverflow = new AtomicLong(0);

    public static void updateQueueHighWaterMark(int currentSize) {
        int currentMax = queueHighWaterMark.get();
        while (currentSize > currentMax) {
            if (queueHighWaterMark.compareAndSet(currentMax, currentSize)) {
                break;
            }
            currentMax = queueHighWaterMark.get();
        }
    }
    
    public static void reset() {
        connectedClients.set(0);
        messagesRouted.set(0);
        queueHighWaterMark.set(0);
        rejectedServerFull.set(0);
        rejectedTooManyConnections.set(0);
        rejectedRateLimit.set(0);
        rejectedBufferOverflow.set(0);
    }
}
