package com.e2eechat.core.session;

import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What happens to a session's counters when it takes a new key.
 *
 * <p>Nonce uniqueness is a property of a key, not of a session, so the send counter and the replay
 * window are per-key state. Carrying either across a renewal breaks something: a send counter that
 * keeps climbing means renewing never lifts the send ceiling, and a replay window that keeps its
 * floor rejects every message the peer sends under the new key, because their counter has started
 * again from one.
 */
public class SessionKeyRenewalTest {

    private Session session;

    @Before
    public void setUp() {
        session = new Session("peer");
    }

    private static SecretKey key(byte seed) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, seed);
        return new SecretKeySpec(bytes, "AES");
    }

    @Test
    public void countersStartAtOne() {
        session.setSecretKey(key((byte) 1));

        assertEquals(1, session.getNextSendCounter());
        assertEquals(2, session.getNextSendCounter());
    }

    /**
     * Without this, renewing a key does not lift the send ceiling: the counter carries on from
     * where it was, so a session that had exhausted itself stays exhausted for good, and the
     * "rekey required" the old code asked for was impossible to satisfy.
     */
    @Test
    public void aNewKeyStartsAFreshSendCounter() {
        session.setSecretKey(key((byte) 1));
        for (int i = 0; i < 500; i++) {
            session.getNextSendCounter();
        }

        session.setSecretKey(key((byte) 2));

        assertEquals("a renewed key must count from the beginning again",
                1, session.getNextSendCounter());
    }

    /**
     * The other half of the same rule. The peer's counter restarts at one under the new key, and a
     * replay window still floored near the old counter would refuse all of it.
     */
    @Test
    public void aNewKeyClearsTheReplayWindow() {
        session.setSecretKey(key((byte) 1));
        for (long counter = 1; counter <= 2000; counter++) {
            assertTrue(session.registerReceivedCounter(counter));
        }

        session.setSecretKey(key((byte) 2));

        assertTrue("the peer's first message under the new key was rejected as a replay",
                session.registerReceivedCounter(1));
        assertTrue(session.registerReceivedCounter(2));
    }

    /** Replay protection has to come straight back after a renewal, not merely be cleared. */
    @Test
    public void theReplayWindowStillWorksAfterARenewal() {
        session.setSecretKey(key((byte) 1));
        session.registerReceivedCounter(1);

        session.setSecretKey(key((byte) 2));
        assertTrue(session.registerReceivedCounter(1));

        assertTrue("a replay under the new key was accepted",
                !session.registerReceivedCounter(1));
    }
}
