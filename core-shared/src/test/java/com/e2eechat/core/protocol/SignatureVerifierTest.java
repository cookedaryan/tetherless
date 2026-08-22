package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import org.junit.Test;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.Assert.*;

public class SignatureVerifierTest {

    @Test
    public void testValidSignature() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair = kpg.generateKeyPair();
        
        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setIv(new byte[12])
                .setPayload("hello".getBytes())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
                
        Message signed = MessageSigner.sign(msg, pair.getPrivate());
        
        SignatureVerifier.VerificationResult result = SignatureVerifier.verify(signed, pair.getPublic());
        assertEquals(SignatureVerifier.VerificationResult.VALID, result);
    }
    
    @Test
    public void testMissingSignature() {
        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setIv(new byte[12])
                .setPayload("hello".getBytes())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
                
        SignatureVerifier.VerificationResult result = SignatureVerifier.verify(msg, null);
        assertEquals(SignatureVerifier.VerificationResult.MISSING_SIGNATURE, result);
    }
    
    @Test
    public void testInvalidSignature() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair1 = kpg.generateKeyPair();
        KeyPair pair2 = kpg.generateKeyPair();
        
        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setIv(new byte[12])
                .setPayload("hello".getBytes())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
                
        Message signed = MessageSigner.sign(msg, pair1.getPrivate());
        
        // Verifying with wrong key
        SignatureVerifier.VerificationResult result = SignatureVerifier.verify(signed, pair2.getPublic());
        assertEquals(SignatureVerifier.VerificationResult.INVALID, result);
    }
}
