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
        DELIVER, DROP_REPLAY, DROP_BAD_SIGNATURE, DROP_NO_SESSION, REKEY_REQUIRED, HANDSHAKE_PROCEED, HANDSHAKE_DROPPED
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
            
            if (!session.registerReceivedCounter(counter)) {
                return new ProcessResult(Outcome.DROP_REPLAY, null);
            }

            try {
                byte[] plaintext = AESUtils.decrypt(msg.getPayload(), session.getSecretKey(), iv);
                return new ProcessResult(Outcome.DELIVER, plaintext);
            } catch (Exception e) {
                return new ProcessResult(Outcome.DROP_NO_SESSION, null); 
            }
        }
        
        return new ProcessResult(Outcome.DELIVER, msg.getPayload());
    }
    
    public byte[] generateIv(Session session, boolean isInitiator) {
        long counter = session.getNextSendCounter();
        ByteBuffer bb = ByteBuffer.allocate(12);
        bb.putInt(isInitiator ? 1 : 0);
        bb.putLong(counter);
        return bb.array();
    }
}
