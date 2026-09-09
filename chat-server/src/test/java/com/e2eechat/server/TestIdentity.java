package com.e2eechat.server;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.protocol.MessageSigner;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A relay client's identity, for tests.
 *
 * <p>The relay no longer takes a claimed id at face value: registration has to present the
 * identity key the id was derived from and sign the frame with it. So a test can no longer call
 * itself {@code "alice"} - it needs a real keypair, and the id that falls out of it.
 *
 * <p>Identities are cached by label so that two places naming {@code "alice"} get the same one,
 * and so a suite does not pay for RSA-2048 keygen once per call site.
 */
final class TestIdentity {

    private static final Map<String, TestIdentity> CACHE = new ConcurrentHashMap<>();

    private final KeyPair keys;
    private final String peerId;

    private TestIdentity() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            this.keys = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA is not available", e);
        }
        this.peerId = PeerId.of(keys.getPublic());
    }

    /** The identity known by {@code label} in this JVM, created on first use. */
    static TestIdentity named(String label) {
        return CACHE.computeIfAbsent(label, ignored -> new TestIdentity());
    }

    /** The routing id, which is the hash of the public key. */
    String peerId() {
        return peerId;
    }

    /**
     * A registration that claims {@code claimedId} while presenting <em>this</em> identity's key.
     *
     * <p>The squatting attack in one frame: peer ids are public, so anyone can name somebody
     * else's. What they cannot do is produce the key it was derived from.
     */
    Message registrationClaiming(String claimedId) throws Exception {
        Message hello = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(claimedId)
                .setPayload(HelloPayload.encode(keys.getPublic(), ""))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        return MessageSigner.sign(hello, keys.getPrivate());
    }

    /** A fresh, signed registration frame. Fresh because the relay refuses a replayed one. */
    Message registrationHello() throws Exception {
        Message hello = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(peerId)
                .setPayload(HelloPayload.encode(keys.getPublic(), ""))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        return MessageSigner.sign(hello, keys.getPrivate());
    }
}
