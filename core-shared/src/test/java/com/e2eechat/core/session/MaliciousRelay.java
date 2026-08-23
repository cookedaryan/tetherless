package com.e2eechat.core.session;

import com.e2eechat.core.crypto.RSAUtils;
import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.protocol.MessageCodec;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A relay that is allowed to do its worst, for testing the claim the whole project rests on: that
 * the server can route ciphertext without being able to read or forge it.
 *
 * <p>The relay sits between two real {@link SecureChat} instances and may rewrite, duplicate,
 * reorder, drop or forge anything passing through, exactly as a compromised or hostile operator
 * could. Each test installs a {@link Tamper} and asserts what the receiving client concluded.
 *
 * <p>Frames are encoded and decoded on the way through, so the relay only ever handles what really
 * travels on the wire - it cannot cheat by reaching into an object the transport would not carry.
 */
public class MaliciousRelay {

    /** Rewrites a frame in flight. Return null to drop it. */
    public interface Tamper {
        Message apply(Message message);
    }

    /** One end of the conversation: an identity, a keystore, and its chat engine. */
    public static final class Endpoint {
        public final String peerId;
        public final KeyPair identity;
        public final InMemoryKeyStore keyStore;
        public final SessionManager sessions;
        public SecureChat chat;

        /** Everything this endpoint concluded, newest last. */
        public final List<SecureChat.Result> results = new ArrayList<>();

        Endpoint(String name, KeyPair identity) throws Exception {
            this.identity = identity;
            this.peerId = PeerId.of(identity.getPublic());
            this.keyStore = new InMemoryKeyStore(identity);
            Function<String, PublicKey> lookup = id -> keyStore.getPeerKey(id).orElse(null);
            this.sessions = new SessionManager(peerId, lookup);
        }

        /** Text this endpoint actually surfaced to its user. */
        public List<String> delivered() {
            List<String> out = new ArrayList<>();
            for (SecureChat.Result r : results) {
                if (r.outcome == SecureChat.Outcome.DELIVERED && r.plaintext != null) {
                    out.add(new String(r.plaintext, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            return out;
        }

        public boolean sawOutcome(SecureChat.Outcome outcome) {
            for (SecureChat.Result r : results) {
                if (r.outcome == outcome) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Minimal keystore: the local identity plus whatever peers we have been introduced to. */
    public static final class InMemoryKeyStore implements IdentityKeyStore {
        private final KeyPair identity;
        private final Map<String, PublicKey> peers = new ConcurrentHashMap<>();

        InMemoryKeyStore(KeyPair identity) {
            this.identity = identity;
        }

        @Override
        public KeyPair loadOrCreateIdentity(char[] passphrase) {
            return identity;
        }

        @Override
        public void storePeerKey(String peerId, PublicKey key) {
            peers.put(peerId, key);
        }

        @Override
        public Optional<PublicKey> getPeerKey(String peerId) {
            return Optional.ofNullable(peers.get(peerId));
        }

        @Override
        public String fingerprint(PublicKey key) {
            return PeerId.of(key);
        }
    }

    private final Map<String, Endpoint> endpoints = new HashMap<>();

    /** Everything that passed through, so a test can replay or inspect it. */
    public final List<Message> intercepted = new ArrayList<>();

    private Tamper tamper = message -> message;

    /** How many frames the installed tamper actually altered or dropped. */
    private int tamperCount;

    /** Frames the tamper altered or dropped, so a test can assert its attack really happened. */
    public int tamperCount() {
        return tamperCount;
    }

    /** Clears the counter, so a test measures only the attack it is about to run. */
    public void resetTamperCount() {
        tamperCount = 0;
    }

    /** When true, frames are queued rather than delivered, so a test controls the ordering. */
    private boolean holdDelivery;
    private final List<Message> held = new ArrayList<>();

    public Endpoint addEndpoint(String name) throws Exception {
        Endpoint endpoint = new Endpoint(name, RSAUtils.generateKeyPair());
        endpoint.chat = new SecureChat(
                endpoint.peerId, endpoint.identity, endpoint.sessions, endpoint.keyStore,
                message -> route(endpoint.peerId, message),
                (peerId, displayName) -> { },
                name);
        endpoints.put(endpoint.peerId, endpoint);
        return endpoint;
    }

    public void setTamper(Tamper tamper) {
        this.tamper = tamper == null ? message -> message : tamper;
    }

    public void holdDelivery(boolean hold) {
        this.holdDelivery = hold;
    }

    /** Delivers everything held, in the given order of arrival indices. */
    public void releaseHeld(int... order) {
        List<Message> queued = new ArrayList<>(held);
        held.clear();
        boolean previous = holdDelivery;
        holdDelivery = false;
        for (int index : order) {
            deliver(queued.get(index));
        }
        holdDelivery = previous;
    }

    public int heldCount() {
        return held.size();
    }

    /** Routes a frame, applying whatever tampering the test installed. */
    private void route(String from, Message message) {
        Message onWire = throughTheWire(message);
        intercepted.add(onWire);

        Message tampered = tamper.apply(onWire);
        if (tampered != onWire) {
            // Counted so a test can prove its attack actually fired. A tamper that silently never
            // matches would make the whole scenario vacuous while still passing.
            tamperCount++;
        }
        if (tampered == null) {
            return; // dropped by the relay
        }
        if (holdDelivery) {
            held.add(tampered);
            return;
        }
        deliver(tampered);
    }

    private void deliver(Message message) {
        Endpoint target = endpoints.get(message.getReceiverId());
        if (target == null) {
            return;
        }
        target.results.add(target.chat.onMessage(message));
    }

    /**
     * Encodes and decodes a frame, so the relay handles exactly the bytes the transport would carry
     * and cannot accidentally pass along object state that never travels.
     */
    private Message throughTheWire(Message message) {
        try {
            return MessageCodec.decode(MessageCodec.encode(message));
        } catch (Exception e) {
            throw new IllegalStateException("frame did not survive the codec", e);
        }
    }

    /**
     * Submits a frame the way an endpoint's transport would, so tampering and hold/release apply.
     * Tests that build a message themselves must use this rather than {@link #replay}, or the
     * attack under test never actually happens.
     */
    public void submit(Message message) {
        route(message.getSenderId(), message);
    }

    /**
     * Injects a frame straight at its recipient, bypassing the tamper hook. This is the attacker
     * acting directly - replaying a captured frame, or introducing one they forged - rather than
     * modifying traffic in flight.
     */
    public void replay(Message message) {
        deliver(message);
    }

    /** Rebuilds a message with a different payload, leaving the original signature attached. */
    public static Message withPayload(Message original, byte[] payload) {
        MessageBuilder builder = new MessageBuilder()
                .setType(original.getType())
                .setSenderId(original.getSenderId())
                .setReceiverId(original.getReceiverId())
                .setPayload(payload)
                .setIv(original.getIv())
                .setMessageId(original.getMessageId())
                .setTimestamp(original.getTimestamp())
                .setProtocolVersion(original.getProtocolVersion());
        if (original.getSignature() != null) {
            builder.setSignature(original.getSignature());
        }
        return builder.buildUnsigned();
    }

    /** Rebuilds a message with no signature at all. */
    public static Message stripSignature(Message original) {
        return new MessageBuilder()
                .setType(original.getType())
                .setSenderId(original.getSenderId())
                .setReceiverId(original.getReceiverId())
                .setPayload(original.getPayload())
                .setIv(original.getIv())
                .setMessageId(original.getMessageId())
                .setTimestamp(original.getTimestamp())
                .setProtocolVersion(original.getProtocolVersion())
                .buildUnsigned();
    }

    /** Flips one bit of a byte array, the cheapest possible ciphertext corruption. */
    public static byte[] flipOneBit(byte[] original) {
        byte[] copy = original.clone();
        copy[copy.length / 2] ^= 0x01;
        return copy;
    }
}
