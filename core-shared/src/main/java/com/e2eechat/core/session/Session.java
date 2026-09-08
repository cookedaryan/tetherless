package com.e2eechat.core.session;

import javax.crypto.SecretKey;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.Iterator;
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
    
    /**
     * How far behind the highest counter seen a frame may be and still be judged on its own.
     *
     * <p>Anything at or below the floor is refused outright. It cannot be told apart from a replay,
     * and a genuine frame that far behind would already have failed the timestamp check.
     */
    static final int REPLAY_WINDOW = 1024;

    /** Highest counter accepted so far. Counters start at 1, so zero means none yet. */
    private long highestReceivedCounter = 0;

    /**
     * Counters accepted at or above the window floor.
     *
     * <p>This set is not what makes replay protection correct - the floor is. It only distinguishes
     * frames inside the window from one another, so genuine reordering still delivers. That
     * separation is the fix: the previous version had no floor and relied on a set that evicted its
     * oldest entry, so a counter simply stopped being remembered once enough traffic had passed and
     * a captured frame carrying it was accepted a second time.
     */
    private final Set<Long> receivedCounters = new HashSet<Long>();

    public Session(String peerId) {
        this.peerId = peerId;
    }

    public String getPeerId() { return peerId; }
    
    public synchronized State getState() { return state; }
    public synchronized void setState(State state) { this.state = state; }

    public synchronized SecretKey getSecretKey() { return secretKey; }

    /**
     * Adopts a freshly agreed key and establishes the session.
     *
     * <p>Both counter spaces reset with it, because nonce uniqueness is a property of a key rather
     * than of a session. Carrying either across a renewal breaks something. A send counter that
     * kept climbing would mean renewing never lifted the send ceiling - which is what made the old
     * "rekey required" impossible to satisfy. A replay window that kept its floor would reject
     * every message the peer sent afterwards, since their counter starts again from one.
     *
     * <p>Discarding the seen counters is safe: a frame captured under the previous key fails
     * authentication under this one, so it cannot be replayed back in through the gap.
     */
    public synchronized void setSecretKey(SecretKey secretKey) {
        this.state = State.ESTABLISHED;
        this.secretKey = secretKey;
        this.sendCounter = 0;
        this.highestReceivedCounter = 0;
        this.receivedCounters.clear();
    }

    public synchronized PublicKey getLocalDhPublicKey() { return localDhPublicKey; }
    public synchronized void setLocalDhPublicKey(PublicKey key) { this.localDhPublicKey = key; }

    public synchronized PublicKey getRemoteDhPublicKey() { return remoteDhPublicKey; }
    public synchronized void setRemoteDhPublicKey(PublicKey key) { this.remoteDhPublicKey = key; }

    /**
     * Messages one key may encrypt before the session renews itself.
     *
     * <p>Well below anything AES-GCM requires - the counter is 64-bit and the direction bit keeps
     * the two peers apart, so nonces would not collide for far longer than this. The budget is
     * about key lifetime rather than nonce space: each renewal is a fresh Diffie-Hellman exchange,
     * so bounding how long one key is used bounds how much a compromise of it reveals.
     */
    public static final long MAX_SENDS_PER_KEY = 100000;

    /**
     * True once this key has encrypted its whole budget and the session needs a new one.
     *
     * <p>Callers check this and renew. {@link #getNextSendCounter()} still refuses to go past the
     * budget, so a caller that does not check cannot quietly spend more of the key than intended.
     */
    public synchronized boolean isSendBudgetExhausted() {
        return sendCounter >= MAX_SENDS_PER_KEY;
    }

    public synchronized long getNextSendCounter() {
        if (sendCounter >= MAX_SENDS_PER_KEY) {
            throw new IllegalStateException(
                    "This key has encrypted its budget of " + MAX_SENDS_PER_KEY
                            + " messages. A new one has to be negotiated before sending again.");
        }
        return ++sendCounter;
    }

    /**
     * Records a received counter.
     *
     * @return false if the frame must be dropped: either it has been seen before, or it is too far
     *         behind to be told apart from a replay
     */
    public synchronized boolean registerReceivedCounter(long counter) {
        if (counter <= highestReceivedCounter - REPLAY_WINDOW) {
            return false;
        }
        if (!receivedCounters.add(counter)) {
            return false;
        }
        if (counter > highestReceivedCounter) {
            highestReceivedCounter = counter;
            forgetCountersBelowTheWindow();
        }
        return true;
    }

    /**
     * Drops counters the floor already rejects, so a long session does not accumulate every one it
     * has ever seen.
     *
     * <p>Only once the set has grown to twice the window, rather than on every message: nothing
     * depends on it for correctness, and pruning eagerly would walk the whole set on every frame to
     * remove a single entry.
     */
    private void forgetCountersBelowTheWindow() {
        if (receivedCounters.size() <= REPLAY_WINDOW * 2) {
            return;
        }
        long floor = highestReceivedCounter - REPLAY_WINDOW;
        Iterator<Long> seen = receivedCounters.iterator();
        while (seen.hasNext()) {
            if (seen.next() <= floor) {
                seen.remove();
            }
        }
    }
}
