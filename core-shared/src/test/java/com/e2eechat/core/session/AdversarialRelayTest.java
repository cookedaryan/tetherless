package com.e2eechat.core.session;

import com.e2eechat.core.crypto.DHUtils;
import com.e2eechat.core.crypto.RSAUtils;
import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.protocol.MessageSigner;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * INTEG-03. Tests the claim the project rests on: a hostile relay can route ciphertext but cannot
 * read it, forge it, or insert itself into a session.
 *
 * <p>Until now that claim was backed by reading {@link SecureChat}, not by anything trying to break
 * it. Each test here gives the relay a specific capability a compromised operator would have and
 * asserts what the receiving client concluded. <strong>Any scenario where a client surfaces
 * attacker-influenced content is a release blocker</strong>, so the assertions are deliberately
 * about what reached the user, not merely about what was logged.
 */
public class AdversarialRelayTest {

    private MaliciousRelay relay;
    private MaliciousRelay.Endpoint alice;
    private MaliciousRelay.Endpoint bob;

    @Before
    public void setUp() throws Exception {
        relay = new MaliciousRelay();
        alice = relay.addEndpoint("Alice");
        bob = relay.addEndpoint("Bob");
    }

    /** An honest relay: the baseline every attack below is measured against. */
    @Test
    public void honestRelayDeliversTheMessage() throws Exception {
        establishSession();

        alice.chat.startHandshake(bob.peerId);
        send(alice, bob, "hello bob");

        assertEquals(java.util.Collections.singletonList("hello bob"), bob.delivered());
        assertTrue(alice.chat.isEstablished(bob.peerId));
    }

    // ------------------------------------------------------------------ MITM

    /**
     * The headline attack: the relay swaps the Diffie-Hellman public key during the exchange so it
     * can derive a key with each side and read everything.
     *
     * <p>It fails because the key exchange is signed. The relay cannot re-sign a substituted key
     * without the peer's identity private key, so the receiver rejects the frame outright and no
     * session is ever established.
     */
    @Test
    public void relayCannotSubstituteDhKeysDuringTheExchange() throws Exception {
        KeyPair relayDh = DHUtils.generateKeyPair();

        relay.setTamper(message -> {
            if (message.getType() == MessageType.KEY_EXCHANGE_INIT
                    || message.getType() == MessageType.KEY_EXCHANGE_REPLY) {
                return MaliciousRelay.withPayload(message, relayDh.getPublic().getEncoded());
            }
            return message;
        });

        alice.chat.startHandshake(bob.peerId);

        assertTrue("the attack never fired", relay.tamperCount() > 0);
        assertFalse("Bob must not establish a session from a substituted key",
                bob.chat.isEstablished(alice.peerId));
        assertFalse("Alice must not establish a session from a substituted key",
                alice.chat.isEstablished(bob.peerId));
        assertTrue("the substituted key exchange must be dropped",
                bob.sawOutcome(SecureChat.Outcome.DROPPED));
    }

    /**
     * The same attack one step earlier: the relay replaces the identity key inside HELLO, so that
     * trust-on-first-use would pin the relay's key instead of the peer's.
     *
     * <p>It fails on the id-to-key binding: a peer id is the hash of the identity key, so a HELLO
     * whose sender id does not match the key it carries is rejected before anything is stored.
     */
    @Test
    public void relayCannotSubstituteTheIdentityKeyInHello() throws Exception {
        KeyPair relayIdentity = RSAUtils.generateKeyPair();

        relay.setTamper(message -> {
            if (message.getType() == MessageType.HELLO) {
                try {
                    return MaliciousRelay.withPayload(message,
                            HelloPayload.encode(relayIdentity.getPublic(), "Alice"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return message;
        });

        alice.chat.startHandshake(bob.peerId);

        assertTrue("the attack never fired", relay.tamperCount() > 0);
        assertFalse("Bob must not have stored a key for Alice",
                bob.keyStore.getPeerKey(alice.peerId).isPresent());
        assertFalse(bob.chat.isEstablished(alice.peerId));
        assertTrue(bob.sawOutcome(SecureChat.Outcome.DROPPED));
    }

    /**
     * If the relay presents a different key for a peer already known, that must never be accepted
     * silently: it is exactly how an operator would take over an existing conversation.
     */
    @Test
    public void aChangedIdentityKeyIsReportedRatherThanAccepted() throws Exception {
        establishSession();

        // A second identity whose id genuinely matches its key, so the binding check passes and the
        // key-change path is what has to catch it.
        KeyPair impostor = RSAUtils.generateKeyPair();
        String impostorId = PeerId.of(impostor.getPublic());
        bob.keyStore.storePeerKey(impostorId, RSAUtils.generateKeyPair().getPublic());

        Message hello = new com.e2eechat.core.models.MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(impostorId)
                .setReceiverId(bob.peerId)
                .setPayload(HelloPayload.encode(impostor.getPublic(), "Alice"))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        relay.replay(hello);

        assertTrue("a changed key must be surfaced, not accepted",
                bob.sawOutcome(SecureChat.Outcome.PEER_KEY_CHANGED));
    }

    // ------------------------------------------------------------- tampering

    /**
     * The relay flips a single bit of the ciphertext. AES-GCM authenticates as well as encrypts, so
     * the tag check fails and nothing reaches the user - the client must not render partial or
     * garbled plaintext.
     */
    @Test
    public void flippingOneCiphertextBitDropsTheMessage() throws Exception {
        establishSession();

        relay.setTamper(message -> message.getType() == MessageType.TEXT_MESSAGE
                ? MaliciousRelay.withPayload(message, MaliciousRelay.flipOneBit(message.getPayload()))
                : message);

        send(alice, bob, "the original text");

        assertTrue("the attack never fired", relay.tamperCount() > 0);
        assertTrue("no altered message may reach the user", bob.delivered().isEmpty());
        assertTrue(bob.sawOutcome(SecureChat.Outcome.DROPPED));
    }

    /** Stripping the signature must not turn a message into an unauthenticated one that is trusted. */
    @Test
    public void strippingTheSignatureDropsTheMessage() throws Exception {
        establishSession();

        relay.setTamper(message -> message.getType() == MessageType.TEXT_MESSAGE
                ? MaliciousRelay.stripSignature(message)
                : message);

        send(alice, bob, "unsigned please");

        assertTrue(bob.delivered().isEmpty());
        assertTrue(bob.sawOutcome(SecureChat.Outcome.DROPPED));
    }

    /** Rewriting the sender id must not let one peer's traffic appear to come from another. */
    @Test
    public void relayCannotRelabelTheSender() throws Exception {
        establishSession();
        MaliciousRelay.Endpoint mallory = relay.addEndpoint("Mallory");

        // Bob knows Mallory's key, so the frame is checked against the wrong identity.
        bob.keyStore.storePeerKey(mallory.peerId, mallory.identity.getPublic());

        relay.setTamper(message -> {
            if (message.getType() != MessageType.TEXT_MESSAGE) {
                return message;
            }
            return new com.e2eechat.core.models.MessageBuilder()
                    .setType(message.getType())
                    .setSenderId(mallory.peerId)
                    .setReceiverId(message.getReceiverId())
                    .setPayload(message.getPayload())
                    .setIv(message.getIv())
                    .setMessageId(message.getMessageId())
                    .setTimestamp(message.getTimestamp())
                    .setSignature(message.getSignature())
                    .buildUnsigned();
        });

        send(alice, bob, "who sent this?");

        assertTrue("the attack never fired", relay.tamperCount() > 0);
        assertTrue("a relabelled message must not be delivered", bob.delivered().isEmpty());
    }

    // --------------------------------------------------------------- forgery

    /** A third party signing with their own key cannot impersonate a known peer. */
    @Test
    public void forgedMessageFromAThirdIdentityIsRejected() throws Exception {
        establishSession();

        KeyPair attacker = RSAUtils.generateKeyPair();
        Message forged = MessageSigner.sign(new com.e2eechat.core.models.MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId(alice.peerId)          // claims to be Alice
                .setReceiverId(bob.peerId)
                .setPayload("transfer the money".getBytes(StandardCharsets.UTF_8))
                .setIv(new byte[12])
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned(), attacker.getPrivate());   // but signs with its own key

        relay.replay(forged);

        assertTrue("a forged message must never be delivered", bob.delivered().isEmpty());
    }

    // ---------------------------------------------------------------- replay

    /** Re-sending a captured frame must not deliver the message twice. */
    @Test
    public void replayingACapturedMessageIsRejected() throws Exception {
        establishSession();
        send(alice, bob, "only once");
        assertEquals(1, bob.delivered().size());

        Message captured = lastOfType(MessageType.TEXT_MESSAGE);
        relay.replay(captured);
        relay.replay(captured);

        assertEquals("a replayed message must not be delivered again", 1, bob.delivered().size());
    }

    // -------------------------------------------------------- drop & reorder

    /**
     * Dropping acknowledgements degrades delivery reporting but must not affect confidentiality or
     * the session: the messages themselves still arrive.
     */
    @Test
    public void droppingAcknowledgementsDoesNotBreakTheSession() throws Exception {
        establishSession();

        relay.setTamper(message ->
                message.getType() == MessageType.DELIVERY_ACK ? null : message);

        send(alice, bob, "first");
        send(alice, bob, "second");

        assertEquals(java.util.Arrays.asList("first", "second"), bob.delivered());
        assertTrue(alice.chat.isEstablished(bob.peerId));
    }

    /** Messages arriving out of order must both be delivered, not silently dropped as replays. */
    @Test
    public void reorderedMessagesAreStillDelivered() throws Exception {
        establishSession();

        relay.holdDelivery(true);
        send(alice, bob, "sent first");
        send(alice, bob, "sent second");
        assertEquals(2, relay.heldCount());

        relay.releaseHeld(1, 0);   // deliver them the wrong way round

        java.util.List<String> delivered = bob.delivered();
        assertEquals(2, delivered.size());
        assertTrue(delivered.contains("sent first"));
        assertTrue(delivered.contains("sent second"));
    }

    // ------------------------------------------------------- confidentiality

    /** What the relay can actually see: the plaintext must not appear anywhere on the wire. */
    @Test
    public void plaintextNeverAppearsInInterceptedTraffic() throws Exception {
        establishSession();
        String secret = "meet me at the observatory";
        send(alice, bob, secret);

        for (Message intercepted : relay.intercepted) {
            if (intercepted.getPayload() == null) {
                continue;
            }
            String asText = new String(intercepted.getPayload(), StandardCharsets.UTF_8);
            assertFalse("the relay saw plaintext in a " + intercepted.getType() + " frame",
                    asText.contains(secret));
        }
    }

    /** Two sessions must not share a key, or one compromise would unlock another conversation. */
    @Test
    public void separateSessionsDeriveSeparateKeys() throws Exception {
        MaliciousRelay.Endpoint carol = relay.addEndpoint("Carol");

        establishSession();
        alice.chat.startHandshake(carol.peerId);

        assertTrue(alice.chat.isEstablished(bob.peerId));
        assertTrue(alice.chat.isEstablished(carol.peerId));
        assertNotEquals("two conversations must not share a session key",
                keyOf(alice, bob.peerId), keyOf(alice, carol.peerId));
    }

    // ----------------------------------------------------------------- setup

    /** Runs a clean handshake through an honest relay. */
    private void establishSession() throws Exception {
        relay.setTamper(null);
        alice.chat.startHandshake(bob.peerId);
        assertTrue("precondition: the handshake should succeed without interference",
                alice.chat.isEstablished(bob.peerId));
        relay.resetTamperCount();
        relay.intercepted.clear();
        alice.results.clear();
        bob.results.clear();
    }

    /**
     * Sends through the relay's routing path, so whatever tampering the test installed is actually
     * applied. Using the direct-injection path here would silently make every attack a no-op.
     */
    private void send(MaliciousRelay.Endpoint from, MaliciousRelay.Endpoint to, String text)
            throws Exception {
        relay.submit(from.chat.encrypt(to.peerId, UUID.randomUUID().toString(),
                text.getBytes(StandardCharsets.UTF_8)));
    }

    private Message lastOfType(MessageType type) {
        for (int i = relay.intercepted.size() - 1; i >= 0; i--) {
            if (relay.intercepted.get(i).getType() == type) {
                return relay.intercepted.get(i);
            }
        }
        throw new IllegalStateException("no intercepted " + type);
    }

    private String keyOf(MaliciousRelay.Endpoint endpoint, String peerId) {
        return java.util.Base64.getEncoder().encodeToString(
                endpoint.sessions.getSession(peerId).getSecretKey().getEncoded());
    }
}
