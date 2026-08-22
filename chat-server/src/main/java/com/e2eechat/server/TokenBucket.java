package com.e2eechat.server;

public class TokenBucket {
    private final int capacity;
    private final int tokensPerSecond;
    
    private double currentTokens;
    private long lastRefillTimestamp;

    public TokenBucket(int capacity, int tokensPerSecond) {
        this.capacity = capacity;
        this.tokensPerSecond = tokensPerSecond;
        this.currentTokens = capacity;
        this.lastRefillTimestamp = System.currentTimeMillis();
    }

    public synchronized boolean tryConsume() {
        refill();
        if (currentTokens >= 1.0) {
            currentTokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        double elapsedTimeSeconds = (now - lastRefillTimestamp) / 1000.0;
        
        if (elapsedTimeSeconds > 0) {
            double tokensToAdd = elapsedTimeSeconds * tokensPerSecond;
            currentTokens = Math.min(capacity, currentTokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }
}
