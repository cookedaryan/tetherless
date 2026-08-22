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

    public static VerificationResult verify(Message msg, PublicKey key) {
        // Enforce mandatory signatures for certain types
        boolean requiresSignature = (msg.getType() == MessageType.TEXT_MESSAGE || 
                                     msg.getType() == MessageType.KEY_EXCHANGE_INIT || 
                                     msg.getType() == MessageType.KEY_EXCHANGE_REPLY);
                                     
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
