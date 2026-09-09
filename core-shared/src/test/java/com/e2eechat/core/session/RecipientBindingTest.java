package com.e2eechat.core.session;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.MessageSigner;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

/**
 * A frame is only acted on by the client it was addressed to.
 *
 * <p>Nothing used to check this. The signature covers the receiver id, so a frame Bob genuinely
 * signed for Charlie verifies perfectly when a relay hands it to Alice instead - and Alice would go
 * on to advance her own session with Bob on it, or mark her own messages delivered because Bob
 * acknowledged somebody else's. Every case here is a frame with a real signature over a real
 * receiver id: the only thing wrong with it is who it arrived at.
 */
public class RecipientBindingTest {

    private static KeyPair bobIdentity;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        bobIdentity = generator.generateKeyPair();
    }

    private static SessionManager aliceManager() {
        return new SessionManager("alice", id -> bobIdentity.getPublic());
    }

    private static Message signedFromBob(MessageType type, String receiverId) throws Exception {
        Message msg = new MessageBuilder()
                .setType(type)
                .setSenderId("bob")
                .setReceiverId(receiverId)
                .setPayload("payload".getBytes())
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        return MessageSigner.sign(msg, bobIdentity.getPrivate());
    }

    @Test
    public void aHandshakeAddressedToSomebodyElseIsDropped() throws Exception {
        SessionManager alice = aliceManager();

        SessionManager.ProcessResult result =
                alice.onMessage(signedFromBob(MessageType.KEY_EXCHANGE_INIT, "charlie"));

        assertEquals(SessionManager.Outcome.DROP_WRONG_RECIPIENT, result.outcome);
        assertEquals("Alice's session with Bob must not have moved",
                Session.State.IDLE, alice.getSession("bob").getState());
    }

    @Test
    public void anAcknowledgementAddressedToSomebodyElseIsDropped() throws Exception {
        assertEquals(SessionManager.Outcome.DROP_WRONG_RECIPIENT,
                aliceManager().onMessage(signedFromBob(MessageType.DELIVERY_ACK, "charlie")).outcome);
    }

    @Test
    public void aReadReceiptAddressedToSomebodyElseIsDropped() throws Exception {
        assertEquals(SessionManager.Outcome.DROP_WRONG_RECIPIENT,
                aliceManager().onMessage(signedFromBob(MessageType.READ_RECEIPT, "charlie")).outcome);
    }

    /** The same frame, correctly addressed, is processed - the check is on the recipient alone. */
    @Test
    public void theSameFrameAddressedHereIsAccepted() throws Exception {
        SessionManager.ProcessResult result =
                aliceManager().onMessage(signedFromBob(MessageType.DELIVERY_ACK, "alice"));

        assertEquals(SessionManager.Outcome.DELIVER, result.outcome);
    }
}
