package com.e2eechat.core.session;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.protocol.ProtocolVectors;

import org.junit.Before;
import org.junit.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * AES-GCM is catastrophically broken by nonce reuse: encrypting two different messages under the
 * same key and IV leaks their XOR and lets an attacker recover the authentication subkey, which
 * makes forgery possible. Both peers in a session share one derived key, so the two directions must
 * never produce the same IV.
 */
public class IvReuseTest {

    private MaliciousRelay relay;
    private MaliciousRelay.Endpoint alice;
    private MaliciousRelay.Endpoint bob;

    @Before
    public void setUp() throws Exception {
        relay = new MaliciousRelay();
        alice = relay.addEndpoint("Alice");
        bob = relay.addEndpoint("Bob");
        alice.chat.startHandshake(bob.peerId);
        assertTrue("precondition: session established", alice.chat.isEstablished(bob.peerId));
    }

    /** The two directions must not collide on their first message, nor on any later one. */
    @Test
    public void oppositeDirectionsNeverShareAnIv() throws Exception {
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 20; i++) {
            Message fromAlice = alice.chat.encrypt(bob.peerId, UUID.randomUUID().toString(),
                    ("a" + i).getBytes(StandardCharsets.UTF_8));
            Message fromBob = bob.chat.encrypt(alice.peerId, UUID.randomUUID().toString(),
                    ("b" + i).getBytes(StandardCharsets.UTF_8));

            String aliceIv = ProtocolVectors.toHex(fromAlice.getIv());
            String bobIv = ProtocolVectors.toHex(fromBob.getIv());

            assertNotEquals("both directions produced the same IV under one shared key,"
                            + " which breaks AES-GCM outright",
                    aliceIv, bobIv);
            assertTrue("IV repeated within a direction: " + aliceIv, seen.add(aliceIv));
            assertTrue("IV repeated within a direction: " + bobIv, seen.add(bobIv));
        }
    }

    /** A single sender must never repeat an IV either. */
    @Test
    public void oneSenderNeverRepeatsAnIv() throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Message msg = alice.chat.encrypt(bob.peerId, UUID.randomUUID().toString(),
                    ("m" + i).getBytes(StandardCharsets.UTF_8));
            assertTrue("IV reused after " + i + " messages",
                    seen.add(ProtocolVectors.toHex(msg.getIv())));
        }
    }

    // ------------------------------------------------------------- at scale

    /**
     * A session driven by hand, without the signing an end-to-end send would do.
     *
     * <p>The tests above go through {@code SecureChat}, which RSA-signs every message - fine for
     * twenty of them, hopeless for a hundred thousand. The nonce is built by {@code
     * SessionManager.generateIv} from the session's counter, so driving that directly exercises
     * the thing under test at full scale in about a second.
     */
    private static SessionManager managerWithEstablishedSession(String self, String peer,
                                                                byte keySeed) {
        SessionManager manager = new SessionManager(self, id -> null);
        Session session = manager.getSession(peer);
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, keySeed);
        session.setSecretKey(new SecretKeySpec(key, "AES"));
        return manager;
    }

    /**
     * Every nonce a key is allowed to produce, checked for repeats.
     *
     * <p>Two hundred messages proved the counter increments. It could not have caught a defect
     * that only appears at scale - a counter that wraps, saturates, or is truncated somewhere in
     * the nonce - because it never went near the budget. This spends the whole budget.
     */
    @Test
    public void noNonceRepeatsAcrossAnEntireKeyBudget() {
        SessionManager manager = managerWithEstablishedSession("alice", "bob", (byte) 1);
        Session session = manager.getSession("bob");

        Set<String> seen = new HashSet<>((int) (Session.MAX_SENDS_PER_KEY / 0.75f) + 16);
        for (long i = 0; i < Session.MAX_SENDS_PER_KEY; i++) {
            String iv = ProtocolVectors.toHex(manager.generateIv(session, true));
            assertTrue("nonce repeated after " + i + " messages: " + iv, seen.add(iv));
        }

        assertEquals("the budget should be exactly spent",
                Session.MAX_SENDS_PER_KEY, seen.size());
        assertTrue("the key should now be out of budget", session.isSendBudgetExhausted());

        try {
            manager.generateIv(session, true);
            fail("a spent key must not hand out another nonce");
        } catch (IllegalStateException expected) {
            // The budget is what stops a key being used past its allowance.
        }
    }

    /** The two directions stay apart for as long as the key lives, not just at the start. */
    @Test
    public void theTwoDirectionsNeverCollideAcrossAnEntireKeyBudget() {
        SessionManager alice = managerWithEstablishedSession("alice", "bob", (byte) 2);
        SessionManager bob = managerWithEstablishedSession("bob", "alice", (byte) 2);
        Session aliceSession = alice.getSession("bob");
        Session bobSession = bob.getSession("alice");

        for (long i = 0; i < Session.MAX_SENDS_PER_KEY; i++) {
            // "alice" sorts below "bob", so Alice transmits on 1 and Bob on 0.
            String fromAlice = ProtocolVectors.toHex(alice.generateIv(aliceSession, true));
            String fromBob = ProtocolVectors.toHex(bob.generateIv(bobSession, false));
            if (fromAlice.equals(fromBob)) {
                fail("the two directions produced the same nonce at counter " + (i + 1)
                        + "; both sides share one key, so this breaks AES-GCM outright");
            }
        }
    }

    /**
     * Renewal restarts the counter, so nonces <em>do</em> repeat across keys - by design.
     *
     * <p>Worth stating plainly, because the obvious assertion here is the wrong one. Nonce
     * uniqueness is a property of a key, not of a session: what must never happen is the same
     * nonce twice under the same key. A test demanding globally distinct nonces would fail on
     * correct code and would have to be "fixed" by breaking the counter reset - which is itself
     * load-bearing, since a receiver's replay window starts again from one after a renewal.
     *
     * <p>So this asserts the pair of facts that actually make renewal safe: the counter restarts,
     * and the key underneath it is a different key.
     */
    @Test
    public void nonceSpaceRestartsOnRenewalUnderAFreshKey() {
        SessionManager manager = managerWithEstablishedSession("alice", "bob", (byte) 3);
        Session session = manager.getSession("bob");

        List<String> firstNonceOfEpoch = new ArrayList<>();
        List<javax.crypto.SecretKey> keys = new ArrayList<>();

        for (int epoch = 0; epoch < 4; epoch++) {
            byte[] material = new byte[32];
            java.util.Arrays.fill(material, (byte) (10 + epoch));
            session.setSecretKey(new SecretKeySpec(material, "AES"));
            keys.add(session.getSecretKey());

            Set<String> withinThisKey = new HashSet<>();
            String first = null;
            for (int i = 0; i < 1000; i++) {
                String iv = ProtocolVectors.toHex(manager.generateIv(session, true));
                assertTrue("nonce repeated within one key at message " + i,
                        withinThisKey.add(iv));
                if (first == null) {
                    first = iv;
                }
            }
            firstNonceOfEpoch.add(first);
        }

        for (int epoch = 1; epoch < firstNonceOfEpoch.size(); epoch++) {
            assertEquals("the counter is expected to restart with a new key",
                    firstNonceOfEpoch.get(0), firstNonceOfEpoch.get(epoch));
            assertNotEquals("a repeated nonce is only safe because the key changed",
                    keys.get(0), keys.get(epoch));
        }
    }

    /** A renewal must not leave the old counter behind, which would skip the budget upward. */
    @Test
    public void aRenewalMidwayThroughTheBudgetStartsTheCounterAgain() {
        SessionManager manager = managerWithEstablishedSession("alice", "bob", (byte) 4);
        Session session = manager.getSession("bob");

        for (int i = 0; i < 5000; i++) {
            manager.generateIv(session, true);
        }
        String beforeRenewal = ProtocolVectors.toHex(manager.generateIv(session, true));

        byte[] fresh = new byte[32];
        java.util.Arrays.fill(fresh, (byte) 99);
        session.setSecretKey(new SecretKeySpec(fresh, "AES"));

        String afterRenewal = ProtocolVectors.toHex(manager.generateIv(session, true));

        assertNotEquals("the counter carried across the renewal", beforeRenewal, afterRenewal);
        assertEquals("a renewed key should count from one again",
                ProtocolVectors.toHex(new byte[]{0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1}),
                afterRenewal);
        assertTrue("a renewed key should have its whole budget back",
                !session.isSendBudgetExhausted());
    }
}
