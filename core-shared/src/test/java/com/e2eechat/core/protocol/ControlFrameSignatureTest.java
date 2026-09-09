package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

/**
 * The types that must not be accepted unsigned.
 *
 * <p>{@link SignatureVerifier} used to name the three types that <em>required</em> a signature and
 * pass everything else through as valid when none was present. So a relay could mint a
 * {@code DELIVERY_ACK} or {@code READ_RECEIPT} out of nothing - two ticks against a message the
 * peer never received, a conversation marked read that nobody opened - or send a
 * {@code KEY_EXCHANGE_REJECT} to break a handshake, all without holding a private key.
 *
 * <p>The existing tampering tests only ever stripped signatures from {@code TEXT_MESSAGE}, which is
 * why that went unnoticed. These name each type on purpose.
 */
public class ControlFrameSignatureTest {

    private static KeyPair identity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        identity = generator.generateKeyPair();
    }

    private static Message unsigned(MessageType type) {
        return new MessageBuilder()
                .setType(type)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setPayload("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
    }

    private static void mustRequireSignature(MessageType type) {
        assertEquals(type + " must not be accepted without a signature",
                SignatureVerifier.VerificationResult.MISSING_SIGNATURE,
                SignatureVerifier.verify(unsigned(type), identity.getPublic()));
    }

    @Test
    public void anUnsignedDeliveryAckIsRefused() {
        mustRequireSignature(MessageType.DELIVERY_ACK);
    }

    @Test
    public void anUnsignedReadReceiptIsRefused() {
        mustRequireSignature(MessageType.READ_RECEIPT);
    }

    @Test
    public void anUnsignedTypingNoticeIsRefused() {
        mustRequireSignature(MessageType.TYPING);
    }

    @Test
    public void anUnsignedKeyExchangeRejectIsRefused() {
        mustRequireSignature(MessageType.KEY_EXCHANGE_REJECT);
    }

    /** Signed properly, the same frames verify - the rule is "signed", not "refused". */
    @Test
    public void theSameFramesPassOnceSigned() throws Exception {
        for (MessageType type : new MessageType[]{MessageType.DELIVERY_ACK,
                MessageType.READ_RECEIPT, MessageType.TYPING, MessageType.KEY_EXCHANGE_REJECT}) {
            Message signed = MessageSigner.sign(unsigned(type), identity.getPrivate());
            assertEquals(type + " should verify when properly signed",
                    SignatureVerifier.VerificationResult.VALID,
                    SignatureVerifier.verify(signed, identity.getPublic()));
        }
    }

    /**
     * The relay's own frames stay unsigned. It has no identity key in this protocol, and a client
     * handles these before any peer verification runs.
     */
    @Test
    public void relayFramesRemainUnsigned() {
        for (MessageType type : new MessageType[]{MessageType.HELLO_ACK, MessageType.ERROR,
                MessageType.PING, MessageType.PONG, MessageType.DISCONNECT}) {
            assertEquals(type + " is relay traffic and carries no peer signature",
                    SignatureVerifier.VerificationResult.VALID,
                    SignatureVerifier.verify(unsigned(type), identity.getPublic()));
        }
    }
}
