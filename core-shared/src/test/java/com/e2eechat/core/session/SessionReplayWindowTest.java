package com.e2eechat.core.session;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The anti-replay window.
 *
 * <p>One property matters above the rest and is asserted from several directions: <strong>a counter
 * that has been accepted is never accepted a second time</strong>, no matter how much traffic has
 * passed in between. Everything else here is about not breaking genuine reordering while holding
 * that line.
 */
public class SessionReplayWindowTest {

    /** Mirrors {@code Session.REPLAY_WINDOW}. */
    private static final int WINDOW = 1024;

    private Session session;

    @Before
    public void setUp() {
        session = new Session("peer");
    }

    @Test
    public void aCounterNotSeenBeforeIsAccepted() {
        assertTrue(session.registerReceivedCounter(1));
        assertTrue(session.registerReceivedCounter(2));
        assertTrue(session.registerReceivedCounter(3));
    }

    @Test
    public void theSameCounterTwiceIsRejected() {
        assertTrue(session.registerReceivedCounter(7));

        assertFalse("a counter seen a moment ago was accepted again",
                session.registerReceivedCounter(7));
    }

    /** Frames genuinely overtake each other. Within the window that must still deliver. */
    @Test
    public void reorderingInsideTheWindowStillDelivers() {
        assertTrue(session.registerReceivedCounter(5));
        assertTrue(session.registerReceivedCounter(3));
        assertTrue(session.registerReceivedCounter(9));
        assertTrue(session.registerReceivedCounter(4));
        assertTrue(session.registerReceivedCounter(1));

        assertFalse(session.registerReceivedCounter(3));
        assertFalse(session.registerReceivedCounter(9));
    }

    /**
     * The case the old implementation got wrong.
     *
     * <p>Seen counters were held in a set capped at 1024 that evicted its oldest entry, so once
     * enough traffic had passed the earliest counter was simply forgotten and a captured frame
     * carrying it would be accepted a second time. Replay protection has to be bounded by how old
     * a counter is, not by how many have arrived since.
     */
    @Test
    public void aCounterForgottenByLaterTrafficIsStillRejected() {
        assertTrue("the first message should be accepted", session.registerReceivedCounter(1));

        // Enough traffic to push counter 1 out of any 1024-entry set.
        for (long counter = 2; counter <= WINDOW * 2; counter++) {
            assertTrue("legitimate counter " + counter + " was rejected",
                    session.registerReceivedCounter(counter));
        }

        assertFalse("a replayed frame was accepted once the window had moved past it",
                session.registerReceivedCounter(1));
    }

    /** The same thing stated as an invariant, over a long run with replays mixed in. */
    @Test
    public void noCounterIsEverAcceptedTwiceAcrossALongSession() {
        for (long counter = 1; counter <= WINDOW * 3; counter++) {
            assertTrue(session.registerReceivedCounter(counter));

            // Replay something well behind the window on every step.
            long stale = counter - WINDOW * 2;
            if (stale >= 1) {
                assertFalse("counter " + stale + " was accepted a second time at " + counter,
                        session.registerReceivedCounter(stale));
            }
        }
    }

    @Test
    public void aCounterFromLongBeforeTheWindowIsRejected() {
        assertTrue(session.registerReceivedCounter(50000));

        assertFalse(session.registerReceivedCounter(1));
        assertFalse(session.registerReceivedCounter(1000));
    }

    /** A counter still inside the window is remembered individually, not merely floored. */
    @Test
    public void theOldestCounterStillInsideTheWindowIsRemembered() {
        for (long counter = 1; counter <= WINDOW; counter++) {
            assertTrue(session.registerReceivedCounter(counter));
        }

        assertFalse("the oldest in-window counter was forgotten",
                session.registerReceivedCounter(1));
    }

    /**
     * Late delivery beyond the window is dropped. This is the cost of the fix and is deliberate:
     * a frame more than {@code WINDOW} behind cannot be told apart from a replay, and the
     * five-minute timestamp check means a genuine one that far back would already be refused.
     */
    @Test
    public void aFirstSightingFromBeyondTheWindowIsDroppedRatherThanTrusted() {
        for (long counter = 2000; counter < 2000 + WINDOW * 2; counter++) {
            assertTrue(session.registerReceivedCounter(counter));
        }

        assertFalse("a counter far behind the window should not be trusted on first sight",
                session.registerReceivedCounter(1500));
    }

    @Test
    public void newCountersAreStillAcceptedAfterHeavyTraffic() {
        for (long counter = 1; counter <= WINDOW * 5; counter++) {
            assertTrue(session.registerReceivedCounter(counter));
        }

        assertTrue(session.registerReceivedCounter(WINDOW * 5 + 1));
        assertTrue(session.registerReceivedCounter(WINDOW * 5 + 2));
    }

    @Test
    public void twoSessionsDoNotShareAWindow() {
        Session other = new Session("other-peer");

        assertTrue(session.registerReceivedCounter(1));

        assertTrue("one peer's counter suppressed another's", other.registerReceivedCounter(1));
    }

    /**
     * The remembered set has to stay bounded, or a long session accumulates every counter it has
     * ever seen. Correctness does not depend on this - the window floor is what rejects - so it is
     * checked by looking at the field rather than by contriving an observable symptom.
     */
    @Test
    public void theRememberedSetStaysBounded() throws Exception {
        for (long counter = 1; counter <= WINDOW * 20; counter++) {
            session.registerReceivedCounter(counter);
        }

        assertTrue("the replay set grew to " + rememberedCount() + " entries",
                rememberedCount() <= WINDOW * 2);
    }

    /** Reads whichever collection the session keeps its seen counters in. */
    private int rememberedCount() throws Exception {
        for (Field field : Session.class.getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(session);
            if (value instanceof Set) {
                return ((Set<?>) value).size();
            }
            if (value instanceof Map) {
                return ((Map<?, ?>) value).size();
            }
            if (value instanceof Collection) {
                return ((Collection<?>) value).size();
            }
        }
        throw new IllegalStateException("no collection of seen counters found on Session");
    }
}
