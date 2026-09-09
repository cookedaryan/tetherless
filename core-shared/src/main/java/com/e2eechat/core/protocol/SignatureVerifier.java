package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;

import java.security.PublicKey;
import java.security.Signature;

public class SignatureVerifier {
    
    public enum VerificationResult {
        VALID, 
        INVALID, 
        NO_KEY_FOR_SENDER, 
        UNSUPPORTED_ALGORITHM,
        MISSING_SIGNATURE
    }

    /**
     * True for the handful of types that legitimately arrive without a peer signature.
     *
     * <p>Everything else is traffic from another client and must be signed. The list is stated as
     * the exception rather than the rule deliberately: this used to name the types that
     * <em>required</em> a signature, so {@code DELIVERY_ACK}, {@code READ_RECEIPT}, {@code TYPING}
     * and {@code KEY_EXCHANGE_REJECT} were accepted unsigned, and a relay could forge a read
     * receipt or tear down a handshake without holding anybody's private key. Inverting it means a
     * type appended to {@link MessageType} later is signed-by-default, and a new hole has to be
     * opened on purpose rather than by omission.
     *
     * <p>{@code HELLO} is the one peer frame in the list. It is what carries the identity key, so
     * there is nothing to verify it against yet; it is authenticated instead by the sender id
     * being the hash of the key inside it. The rest are exchanged with the relay, not with a peer.
     */
    private static boolean unsignedByDesign(MessageType type) {
        switch (type) {
            case HELLO:
            case HELLO_ACK:
            case ERROR:
            case PING:
            case PONG:
            case DISCONNECT:
                return true;
            default:
                return false;
        }
    }

    public static VerificationResult verify(Message msg, PublicKey key) {
        boolean requiresSignature = !unsignedByDesign(msg.getType());

        if (msg.getSignature() == null || msg.getSignature().length == 0) {
            return requiresSignature ? VerificationResult.MISSING_SIGNATURE : VerificationResult.VALID;
        }

        if (key == null) {
            return VerificationResult.NO_KEY_FOR_SENDER;
        }

        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(msg.canonicalBytesForSigning());
            
            boolean isValid = signature.verify(msg.getSignature());
            return isValid ? VerificationResult.VALID : VerificationResult.INVALID;
        } catch (Exception e) {
            return VerificationResult.UNSUPPORTED_ALGORITHM;
        }
    }
}
