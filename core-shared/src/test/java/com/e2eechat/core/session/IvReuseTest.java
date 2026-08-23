package com.e2eechat.core.session;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.protocol.ProtocolVectors;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
}
