package com.e2eechat.core.session;

import com.e2eechat.core.crypto.AESUtils;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.SignatureVerifier;

import java.nio.ByteBuffer;
import java.security.PublicKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class SessionManager {

    public enum Outcome {
        DELIVER, DROP_REPLAY, DROP_BAD_SIGNATURE, DROP_NO_SESSION, DROP_WRONG_RECIPIENT, DROP_BAD_DIRECTION, REKEY_REQUIRED, HANDSHAKE_PROCEED, HANDSHAKE_DROPPED
    }

    public static class ProcessResult {
        public final Outcome outcome;
        public final byte[] plaintext;

        public ProcessResult(Outcome outcome, byte[] plaintext) {
            this.outcome = outcome;
            this.plaintext = plaintext;
        }
    }

    private final String localClientId;
    private final Function<String, PublicKey> peerKeyLookup;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public SessionManager(String localClientId, Function<String, PublicKey> peerKeyLookup) {
        this.localClientId = localClientId;
        this.peerKeyLookup = peerKeyLookup;
    }

    public Session getSession(String peerId) {
        return sessions.computeIfAbsent(peerId, Session::new);
    }

    public ProcessResult onMessage(Message msg) {
        // Timestamp validation (max 5 minutes skew)
        long now = System.currentTimeMillis();
        if (Math.abs(now - msg.getTimestamp()) > 300000) {
            return new ProcessResult(Outcome.DROP_REPLAY, null); // Clock skew / old replay
        }

        // A frame is ours only if it is addressed to us, and nothing used to check that. The
        // signature covers the receiver id, so a frame Bob genuinely signed for Charlie verifies
        // perfectly when a relay hands it to Alice instead - and Alice would then advance her own
        // session with Bob on it, or mark her own messages delivered. The binding has to be
        // enforced here, before any session state is touched. It also drops a client's own frame
        // reflected back at it.
        if (msg.getReceiverId() != null && !localClientId.equals(msg.getReceiverId())) {
            return new ProcessResult(Outcome.DROP_WRONG_RECIPIENT, null);
        }

        if (msg.getType() == MessageType.HELLO) {
            // Hello messages are either unsigned or self-signed and process differently
            return new ProcessResult(Outcome.DELIVER, msg.getPayload());
        }

        // Verify signature strictly for all other messages
        PublicKey peerIdentityKey = peerKeyLookup.apply(msg.getSenderId());
        if (peerIdentityKey == null) {
            return new ProcessResult(Outcome.DROP_BAD_SIGNATURE, null); // Strictly require key
        }

        SignatureVerifier.VerificationResult sigResult = SignatureVerifier.verify(msg, peerIdentityKey);
        if (sigResult == SignatureVerifier.VerificationResult.INVALID || 
            sigResult == SignatureVerifier.VerificationResult.MISSING_SIGNATURE) {
            return new ProcessResult(Outcome.DROP_BAD_SIGNATURE, null);
        }

        Session session = getSession(msg.getSenderId());

        if (msg.getType() == MessageType.KEY_EXCHANGE_INIT) {
            if (session.getState() == Session.State.HANDSHAKE_SENT) {
                if (localClientId.compareTo(msg.getSenderId()) > 0) {
                    session.setState(Session.State.HANDSHAKE_RECEIVED);
                    return new ProcessResult(Outcome.HANDSHAKE_PROCEED, null);
                } else {
                    return new ProcessResult(Outcome.HANDSHAKE_DROPPED, null);
                }
            }
            session.setState(Session.State.HANDSHAKE_RECEIVED);
            return new ProcessResult(Outcome.HANDSHAKE_PROCEED, null);
            
        } else if (msg.getType() == MessageType.KEY_EXCHANGE_REPLY) {
            if (session.getState() != Session.State.HANDSHAKE_SENT) {
                return new ProcessResult(Outcome.DROP_NO_SESSION, null);
            }
            return new ProcessResult(Outcome.HANDSHAKE_PROCEED, null);
            
        } else if (msg.getType() == MessageType.TEXT_MESSAGE) {
            if (session.getState() != Session.State.ESTABLISHED) {
                return new ProcessResult(Outcome.DROP_NO_SESSION, null);
            }

            byte[] iv = msg.getIv();
            if (iv == null || iv.length != 12) {
                return new ProcessResult(Outcome.DROP_NO_SESSION, null);
            }
            
            ByteBuffer bb = ByteBuffer.wrap(iv);
            int direction = bb.getInt();
            long counter = bb.getLong();

            // The direction bit exists so that two peers sharing one derived key, both counting
            // from zero, never build the same nonce. It was written on the way out and ignored on
            // the way in, which left the guarantee resting entirely on the sender's good
            // behaviour - so a reflected frame, or a peer that got the bit wrong, was accepted
            // against the same key and counter space as our own traffic.
            //
            // Checked before the counter is registered, deliberately. A frame that fails here has
            // not earned a slot in the replay window, and letting it take one would let the wrong
            // direction burn through the receive window and lock out the traffic that belongs
            // there.
            if (direction != expectedDirection(msg.getSenderId())) {
                return new ProcessResult(Outcome.DROP_BAD_DIRECTION, null);
            }

            // Decrypt first, and only then let the counter into the replay window. GCM
            // authenticates as well as encrypts, so reaching past this line means the frame was
            // produced by something holding the session key - not merely by something holding a
            // signing key the sender's identity vouches for.
            //
            // The order is what decides who can move the window. Registering first meant a frame
            // whose ciphertext was pure noise still claimed its counter, so anything able to get a
            // signed frame this far could spend counters the genuine traffic still needed. Same
            // principle as the direction check above: a frame that turns out to be unusable must
            // not leave a mark behind.
            byte[] plaintext;
            try {
                plaintext = AESUtils.decrypt(msg.getPayload(), session.getSecretKey(), iv);
            } catch (Exception e) {
                return new ProcessResult(Outcome.DROP_NO_SESSION, null);
            }

            if (!session.registerReceivedCounter(counter)) {
                return new ProcessResult(Outcome.DROP_REPLAY, null);
            }

            return new ProcessResult(Outcome.DELIVER, plaintext);
        }
        
        return new ProcessResult(Outcome.DELIVER, msg.getPayload());
    }
    
    /**
     * The direction bit a peer must have used on a frame sent to us.
     *
     * <p>The mirror of what {@code SecureChat} passes to {@link #generateIv}: the side whose id
     * sorts lower transmits on 1, the other on 0. Derived from the two ids rather than from who
     * started the handshake, so both ends agree without negotiating it.
     */
    private int expectedDirection(String senderId) {
        return localClientId.compareTo(senderId) > 0 ? 1 : 0;
    }

    /**
     * Builds the 96-bit GCM nonce as {@code [direction:4][counter:8]}.
     *
     * <p>Counter-based rather than random, because a random 96-bit nonce collides often enough to
     * matter over a long session and GCM does not survive nonce reuse.
     *
     * @param directionBit must differ between the two peers of a session. They share one derived
     *                     key and both count from zero, so if both sides pass the same value their
     *                     n-th messages collide on key and nonce together, which leaks the XOR of
     *                     the plaintexts and exposes the authentication subkey. Callers derive it
     *                     from the peer ids rather than from who happened to start the handshake.
     */
    public byte[] generateIv(Session session, boolean directionBit) {
        long counter = session.getNextSendCounter();
        ByteBuffer bb = ByteBuffer.allocate(12);
        bb.putInt(directionBit ? 1 : 0);
        bb.putLong(counter);
        return bb.array();
    }
}
