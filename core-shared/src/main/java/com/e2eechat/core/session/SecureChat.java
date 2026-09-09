package com.e2eechat.core.session;

import com.e2eechat.core.crypto.AESUtils;
import com.e2eechat.core.crypto.DHUtils;
import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.protocol.MessageSigner;

import javax.crypto.AEADBadTagException;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The handshake and message crypto, shared by every client.
 *
 * <p>This exists because the desktop client had grown its own copy of the key exchange, and the
 * mobile client was about to grow a second one. Two implementations of a handshake drift, and the
 * symptom is not a compile error - it is a session that establishes but derives a different key on
 * each side, so every message decodes to noise. One implementation removes the possibility.
 *
 * <p>Platform-specific concerns stay outside: persistence, UI, and how the identity key is stored
 * are all injected. Everything here is plain Java and runs unchanged on the JVM and on Android.
 *
 * <p><strong>Threading:</strong> safe for the reader thread to call {@link #onMessage} while a UI
 * thread calls {@link #encrypt}. Callers must still keep crypto off the Android main thread - a
 * 2048-bit DH operation on a low-end device is long enough to trigger an ANR.
 */
public class SecureChat {

    /** How a received message came out the far side. */
    public enum Outcome {
        /** A text message decrypted successfully; {@link Result#plaintext} holds it. */
        DELIVERED,
        /** Handshake traffic was consumed; nothing to show the user. */
        HANDSHAKE_ADVANCED,
        /** The session is now usable. */
        SESSION_ESTABLISHED,
        /** A peer we had not met sent their key; it has been trusted on first use. */
        PEER_INTRODUCED,
        /** A known peer presented a different identity key. Treat as hostile until re-verified. */
        PEER_KEY_CHANGED,
        /** Dropped: replay, bad signature, no session, or an id that did not match its key. */
        DROPPED
    }

    /** What {@link #onMessage} concluded. */
    public static final class Result {
        public final Outcome outcome;
        public final byte[] plaintext;
        public final String peerId;
        public final String detail;

        Result(Outcome outcome, byte[] plaintext, String peerId, String detail) {
            this.outcome = outcome;
            this.plaintext = plaintext;
            this.peerId = peerId;
            this.detail = detail;
        }

        static Result of(Outcome outcome, String peerId, String detail) {
            return new Result(outcome, null, peerId, detail);
        }
    }

    /** How signed frames leave this client. */
    public interface Transport {
        void send(Message message);
    }

    /** Notified when a peer tells us the name they wish to be shown as. */
    public interface PeerNameSink {
        void onPeerName(String peerId, String displayName);
    }

    private final String clientId;
    private final KeyPair identityKey;
    private final SessionManager sessionManager;
    private final IdentityKeyStore keyStore;
    private final Transport transport;
    private final PeerNameSink peerNames;

    private volatile String localDisplayName;

    /** DH private keys for handshakes we started, until the reply arrives. */
    private final ConcurrentMap<String, PrivateKey> pendingDhKeys = new ConcurrentHashMap<>();

    public SecureChat(String clientId, KeyPair identityKey, SessionManager sessionManager,
                      IdentityKeyStore keyStore, Transport transport,
                      PeerNameSink peerNames, String localDisplayName) {
        this.clientId = clientId;
        this.identityKey = identityKey;
        this.sessionManager = sessionManager;
        this.keyStore = keyStore;
        this.transport = transport;
        this.peerNames = peerNames == null ? (id, name) -> { } : peerNames;
        this.localDisplayName = localDisplayName;
    }

    public String getClientId() {
        return clientId;
    }

    public void setLocalDisplayName(String name) {
        this.localDisplayName = name;
    }

    public Session.State stateOf(String peerId) {
        return sessionManager.getSession(peerId).getState();
    }

    public boolean isEstablished(String peerId) {
        return stateOf(peerId) == Session.State.ESTABLISHED;
    }

    // ------------------------------------------------------------- handshake

    /**
     * Introduces us to a peer and begins the key exchange. Safe to call when a session already
     * exists, in which case it does nothing.
     */
    public void startHandshake(String peerId) throws Exception {
        Session session = sessionManager.getSession(peerId);
        if (session.getState() == Session.State.ESTABLISHED) {
            return;
        }

        transport.send(hello(peerId));

        KeyPair dhPair = DHUtils.generateKeyPair();
        session.setLocalDhPublicKey(dhPair.getPublic());
        pendingDhKeys.put(peerId, dhPair.getPrivate());

        Message init = new MessageBuilder()
                .setType(MessageType.KEY_EXCHANGE_INIT)
                .setSenderId(clientId)
                .setReceiverId(peerId)
                .setPayload(dhPair.getPublic().getEncoded())
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();

        session.setState(Session.State.HANDSHAKE_SENT);
        transport.send(MessageSigner.sign(init, identityKey.getPrivate()));
    }

    /**
     * Discards the current key and negotiates another.
     *
     * <p>The peer needs no special handling: a {@code KEY_EXCHANGE_INIT} on an established session
     * is already answered with a fresh exchange, and both sides reset their counters when they
     * adopt the new key. Messages already in flight under the old key will fail authentication at
     * the far end and be dropped - a renewal is not seamless, and this is where that shows.
     */
    private void renewSession(String peerId) throws Exception {
        resetSession(peerId);
        startHandshake(peerId);
    }

    /** Tears down a session so the next {@link #startHandshake} negotiates fresh keys. */
    public void resetSession(String peerId) {
        sessionManager.getSession(peerId).setState(Session.State.IDLE);
        pendingDhKeys.remove(peerId);
    }

    private Message hello(String peerId) throws Exception {
        return new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(clientId)
                .setReceiverId(peerId)
                .setPayload(HelloPayload.encode(identityKey.getPublic(), localDisplayName))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
    }

    // --------------------------------------------------------------- inbound

    /** Processes one received frame. Never throws; failures come back as {@link Outcome#DROPPED}. */
    public Result onMessage(Message msg) {
        try {
            // Recipient binding, repeated here because HELLO never reaches SessionManager. A relay
            // that hands us a frame addressed to somebody else must not be able to introduce a
            // peer to us, or advance our state, on the strength of a signature made for a third
            // party. See SessionManager.onMessage for the same check on everything else.
            if (msg.getReceiverId() != null && !clientId.equals(msg.getReceiverId())) {
                return Result.of(Outcome.DROPPED, msg.getSenderId(), "WRONG_RECIPIENT");
            }

            if (msg.getType() == MessageType.HELLO) {
                return onHello(msg);
            }

            SessionManager.ProcessResult verdict = sessionManager.onMessage(msg);
            Session session = sessionManager.getSession(msg.getSenderId());

            switch (verdict.outcome) {
                case HANDSHAKE_PROCEED:
                    return onHandshake(msg, session);
                case DELIVER:
                    if (msg.getType() == MessageType.TEXT_MESSAGE) {
                        return new Result(Outcome.DELIVERED, verdict.plaintext,
                                msg.getSenderId(), null);
                    }
                    // Signalling frames carry no ciphertext; hand the payload through as-is.
                    return new Result(Outcome.DELIVERED, msg.getPayload(), msg.getSenderId(), null);
                default:
                    return Result.of(Outcome.DROPPED, msg.getSenderId(),
                            verdict.outcome.name());
            }
        } catch (AEADBadTagException e) {
            // Authentication failed: the ciphertext was altered, or the keys disagree. Never retry
            // and never show the bytes.
            return Result.of(Outcome.DROPPED, msg.getSenderId(), "AUTHENTICATION_FAILED");
        } catch (Exception e) {
            return Result.of(Outcome.DROPPED, msg.getSenderId(), e.getClass().getSimpleName());
        }
    }

    private Result onHello(Message msg) throws Exception {
        HelloPayload hello = HelloPayload.decode(msg.getPayload());
        PublicKey presented = hello.getPublicKey();

        // The sender's id must be the hash of the key they just presented, or anyone could claim
        // someone else's address and have their own key trusted against it on first contact.
        if (!PeerId.of(presented).equals(msg.getSenderId())) {
            return Result.of(Outcome.DROPPED, msg.getSenderId(), "ID_DOES_NOT_MATCH_KEY");
        }

        peerNames.onPeerName(msg.getSenderId(), hello.getDisplayName());

        Optional<PublicKey> known = keyStore.getPeerKey(msg.getSenderId());
        if (!known.isPresent()) {
            keyStore.storePeerKey(msg.getSenderId(), presented);
            // Answer so the peer learns our key too; otherwise only one side can verify.
            transport.send(hello(msg.getSenderId()));
            return Result.of(Outcome.PEER_INTRODUCED, msg.getSenderId(), hello.getDisplayName());
        }
        if (!known.get().equals(presented)) {
            return Result.of(Outcome.PEER_KEY_CHANGED, msg.getSenderId(), null);
        }
        return Result.of(Outcome.HANDSHAKE_ADVANCED, msg.getSenderId(), null);
    }

    private Result onHandshake(Message msg, Session session) throws Exception {
        byte[] salt = new byte[32];
        byte[] info = "tetherless-v1 aes-256-gcm".getBytes(StandardCharsets.UTF_8);

        if (msg.getType() == MessageType.KEY_EXCHANGE_INIT) {
            PublicKey remoteDh = DHUtils.getPublicKeyFromBytes(msg.getPayload());
            session.setRemoteDhPublicKey(remoteDh);

            KeyPair dhPair = DHUtils.generateKeyPair();
            session.setLocalDhPublicKey(dhPair.getPublic());

            byte[] shared = DHUtils.generateSharedSecret(dhPair.getPrivate(), remoteDh, salt, info);
            session.setSecretKey(new SecretKeySpec(shared, "AES"));

            Message reply = new MessageBuilder()
                    .setType(MessageType.KEY_EXCHANGE_REPLY)
                    .setSenderId(clientId)
                    .setReceiverId(msg.getSenderId())
                    .setPayload(dhPair.getPublic().getEncoded())
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .buildUnsigned();
            transport.send(MessageSigner.sign(reply, identityKey.getPrivate()));

            return Result.of(Outcome.SESSION_ESTABLISHED, msg.getSenderId(), null);
        }

        if (msg.getType() == MessageType.KEY_EXCHANGE_REPLY) {
            PrivateKey ownDh = pendingDhKeys.remove(msg.getSenderId());
            if (ownDh == null) {
                return Result.of(Outcome.DROPPED, msg.getSenderId(), "NO_PENDING_HANDSHAKE");
            }
            PublicKey remoteDh = DHUtils.getPublicKeyFromBytes(msg.getPayload());
            session.setRemoteDhPublicKey(remoteDh);

            byte[] shared = DHUtils.generateSharedSecret(ownDh, remoteDh, salt, info);
            session.setSecretKey(new SecretKeySpec(shared, "AES"));

            return Result.of(Outcome.SESSION_ESTABLISHED, msg.getSenderId(), null);
        }

        return Result.of(Outcome.HANDSHAKE_ADVANCED, msg.getSenderId(), null);
    }

    // -------------------------------------------------------------- outbound

    /**
     * Encrypts and signs a message. Refuses when no session exists: there is deliberately no
     * plaintext fallback, since one would be a downgrade waiting to be triggered.
     *
     * @return the signed frame, ready for the transport
     * @throws IllegalStateException if the session is not established
     */
    public Message encrypt(String peerId, String messageId, byte[] plaintext) throws Exception {
        Session session = sessionManager.getSession(peerId);
        if (session.getState() != Session.State.ESTABLISHED) {
            throw new IllegalStateException("No established session with " + peerId);
        }

        // A key that has spent its budget is replaced rather than pushed past. Reaching the limit
        // used to throw and go no further, and since a renewal did not reset the counter either,
        // the conversation was finished for the life of the process. Renewing here means the
        // limit costs one message, which the caller is told about, instead of all of them.
        if (session.isSendBudgetExhausted()) {
            renewSession(peerId);
            throw new SessionRenewalRequiredException(peerId);
        }

        // The direction bit must differ between the two peers. Both sides share one derived key and
        // both count from zero, so passing a constant here made Alice's n-th message and Bob's
        // n-th message use the same key and IV - which breaks AES-GCM outright. Deriving the bit
        // from the ids gives each side a stable, opposite value without extra negotiation.
        boolean lowSide = clientId.compareTo(peerId) < 0;
        byte[] iv = sessionManager.generateIv(session, lowSide);
        byte[] ciphertext = AESUtils.encrypt(plaintext, session.getSecretKey(), iv);

        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId(clientId)
                .setReceiverId(peerId)
                .setPayload(ciphertext)
                .setIv(iv)
                .setMessageId(messageId)
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();

        return MessageSigner.sign(msg, identityKey.getPrivate());
    }

    /** Signs a control frame. These carry no message content, so they are signed but not encrypted. */
    public Message signal(String peerId, MessageType type, byte[] payload) throws Exception {
        Message msg = new MessageBuilder()
                .setType(type)
                .setSenderId(clientId)
                .setReceiverId(peerId)
                .setPayload(payload)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        return MessageSigner.sign(msg, identityKey.getPrivate());
    }
}
