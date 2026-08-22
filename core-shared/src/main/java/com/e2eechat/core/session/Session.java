package com.e2eechat.core.session;

import javax.crypto.SecretKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class Session {
    public enum State {
        IDLE, HANDSHAKE_SENT, HANDSHAKE_RECEIVED, ESTABLISHED, EXPIRED, FAILED
    }

    private final String peerId;
    private State state = State.IDLE;
    private SecretKey secretKey;
    private PublicKey localDhPublicKey;
    private PublicKey remoteDhPublicKey;
    private long sendCounter = 0;
    
    // We use a Map to utilize removeEldestEntry for a size-capped sliding window
    private final Map<Long, Boolean> receivedCountersMap = new LinkedHashMap<Long, Boolean>(1024, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 1024;
        }
    };
    private final Set<Long> receivedCounters = Collections.synchronizedSet(Collections.newSetFromMap(receivedCountersMap));

    public Session(String peerId) {
        this.peerId = peerId;
    }

    public String getPeerId() { return peerId; }
    
    public synchronized State getState() { return state; }
    public synchronized void setState(State state) { this.state = state; }

    public synchronized SecretKey getSecretKey() { return secretKey; }
    public synchronized void setSecretKey(SecretKey secretKey) { this.state = State.ESTABLISHED; this.secretKey = secretKey; }

    public synchronized PublicKey getLocalDhPublicKey() { return localDhPublicKey; }
    public synchronized void setLocalDhPublicKey(PublicKey key) { this.localDhPublicKey = key; }

    public synchronized PublicKey getRemoteDhPublicKey() { return remoteDhPublicKey; }
    public synchronized void setRemoteDhPublicKey(PublicKey key) { this.remoteDhPublicKey = key; }

    public synchronized long getNextSendCounter() {
        if (sendCounter >= 100000) {
            throw new IllegalStateException("Session key exhausted (max sends reached). Rekey required.");
        }
        return ++sendCounter;
    }

    public boolean registerReceivedCounter(long counter) {
        return receivedCounters.add(counter); // returns false if already present (replay)
    }
}
