package com.e2eechat.core.network;

import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The retry schedule, on its own.
 *
 * <p>Separated from the connection tests because it is a pure function of the attempt number, and
 * because asserting a doubling schedule by waiting for it would take a little over two minutes.
 */
public class ConnectionManagerBackoffTest {

    private static KeyPair stub;

    /**
     * An identity for the constructor, which now insists on one. Nothing here signs anything - the
     * schedule under test never touches a socket - so a single pair, generated once, is enough.
     */
    private static KeyPair stubIdentity() throws Exception {
        if (stub == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            stub = generator.generateKeyPair();
        }
        return stub;
    }

    @Test
    public void ceilingDoublesFromOneSecond() {
        assertEquals(1000, ConnectionManager.backoffCeilingMillis(0));
        assertEquals(2000, ConnectionManager.backoffCeilingMillis(1));
        assertEquals(4000, ConnectionManager.backoffCeilingMillis(2));
        assertEquals(8000, ConnectionManager.backoffCeilingMillis(3));
        assertEquals(16000, ConnectionManager.backoffCeilingMillis(4));
        assertEquals(32000, ConnectionManager.backoffCeilingMillis(5));
    }

    @Test
    public void ceilingIsCappedAndStaysThere() {
        assertEquals(ConnectionManager.MAX_BACKOFF_MILLIS,
                ConnectionManager.backoffCeilingMillis(6));
        assertEquals(ConnectionManager.MAX_BACKOFF_MILLIS,
                ConnectionManager.backoffCeilingMillis(ConnectionManager.MAX_ATTEMPT));
        // The attempt counter stops climbing at MAX_ATTEMPT, but a shift past 63 would wrap to a
        // negative delay if it ever did, so the cap is asserted well beyond it.
        assertEquals(ConnectionManager.MAX_BACKOFF_MILLIS,
                ConnectionManager.backoffCeilingMillis(1000));
    }

    @Test
    public void ceilingIsNeverNegative() {
        for (int attempt = 0; attempt <= 100; attempt++) {
            assertTrue("negative ceiling at attempt " + attempt,
                    ConnectionManager.backoffCeilingMillis(attempt) > 0);
        }
    }

    /**
     * Full jitter: the delay is drawn from below the ceiling rather than sitting on it, so a relay
     * coming back up is not met by every client it dropped at the same instant.
     */
    @Test
    public void delayIsJitteredBelowTheCeiling() throws Exception {
        ConnectionManager manager = new ConnectionManager("localhost", 1, "id", stubIdentity(), null);
        for (int attempt = 0; attempt < 8; attempt++) {
            long ceiling = ConnectionManager.backoffCeilingMillis(attempt);
            boolean sawSomethingBelowTheCeiling = false;
            for (int draw = 0; draw < 200; draw++) {
                long delay = manager.backoffDelayMillis(attempt);
                assertTrue("delay " + delay + " is negative", delay >= 0);
                assertTrue("delay " + delay + " exceeds ceiling " + ceiling, delay <= ceiling);
                if (delay < ceiling) {
                    sawSomethingBelowTheCeiling = true;
                }
            }
            assertTrue("attempt " + attempt + " is not jittered", sawSomethingBelowTheCeiling);
        }
    }
}
