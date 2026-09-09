package com.e2eechat.core.session;

import com.e2eechat.core.crypto.AESUtils;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.MessageSigner;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * The direction bit in the GCM nonce, on the receive path.
 *
 * <p>Two peers share one derived key and both count their messages from zero, so the only thing
 * keeping their n-th nonces apart is the four-byte direction field: the side whose id sorts lower
 * transmits on 1, the other on 0. It was written on the way out and never looked at on the way in,
 * which meant the property held only for as long as everybody chose to honour it.
 *
 * <p>"alice" sorts below "bob", so Alice transmits on 1 and Bob on 0.
 */
public class NonceDirectionTest {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    private static KeyPair aliceIdentity;

    /** The shared session key. Its value does not matter; both sides using the same one does. */
    private final SecretKey sessionKey = new SecretKeySpec(new byte[32], "AES");

    private SessionManager bob;

    @BeforeClass
    public static void generateIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        aliceIdentity = generator.generateKeyPair();
    }

    @Before
    public void setUp() {
        bob = new SessionManager(BOB, id -> aliceIdentity.getPublic());
        Session session = bob.getSession(ALICE);
        session.setSecretKey(sessionKey);
        session.setState(Session.State.ESTABLISHED);
    }

    private static byte[] iv(int direction, long counter) {
        ByteBuffer bb = ByteBuffer.allocate(12);
        bb.putInt(direction);
        bb.putLong(counter);
        return bb.array();
    }

    /** A frame from Alice to Bob, on whichever direction bit the caller wants to claim. */
    private Message fromAlice(int direction, long counter, String text) throws Exception {
        byte[] nonce = iv(direction, counter);
        byte[] ciphertext =
                AESUtils.encrypt(text.getBytes(StandardCharsets.UTF_8), sessionKey, nonce);
        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId(ALICE)
                .setReceiverId(BOB)
                .setPayload(ciphertext)
                .setIv(nonce)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        return MessageSigner.sign(msg, aliceIdentity.getPrivate());
    }

    @Test
    public void aFrameOnTheSendersOwnDirectionIsDelivered() throws Exception {
        SessionManager.ProcessResult result = bob.onMessage(fromAlice(1, 1, "hello"));

        assertEquals(SessionManager.Outcome.DELIVER, result.outcome);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), result.plaintext);
    }

    /**
     * The bit Bob transmits on, arriving from Alice. Everything else about the frame is valid -
     * signed by Alice, addressed to Bob, decryptable - so the direction check is the only thing
     * that can catch it.
     */
    @Test
    public void aFrameOnTheReceiversOwnDirectionIsDropped() throws Exception {
        SessionManager.ProcessResult result = bob.onMessage(fromAlice(0, 1, "wrong way"));

        assertEquals(SessionManager.Outcome.DROP_BAD_DIRECTION, result.outcome);
    }

    /** Anything that is neither side's bit is not a direction at all. */
    @Test
    public void aFrameOnAnInventedDirectionIsDropped() throws Exception {
        assertEquals(SessionManager.Outcome.DROP_BAD_DIRECTION,
                bob.onMessage(fromAlice(7, 1, "nonsense")).outcome);
    }

    /**
     * A rejected frame must not consume its counter.
     *
     * <p>The replay window is what stops a counter being accepted twice, so anything allowed to
     * put a counter into it can spend the receive window on the receiver's behalf. If a
     * wrong-direction frame took the slot first, the real frame carrying that counter would be
     * dropped as a replay - which turns the check into a way to silence a conversation rather
     * than a way to protect it.
     */
    @Test
    public void aRejectedFrameDoesNotSpendTheCounter() throws Exception {
        assertEquals(SessionManager.Outcome.DROP_BAD_DIRECTION,
                bob.onMessage(fromAlice(0, 42, "wrong way")).outcome);

        SessionManager.ProcessResult genuine = bob.onMessage(fromAlice(1, 42, "the real one"));

        assertEquals("counter 42 was spent by the frame that was rejected",
                SessionManager.Outcome.DELIVER, genuine.outcome);
        assertArrayEquals("the real one".getBytes(StandardCharsets.UTF_8), genuine.plaintext);
    }

    /** Both ends derive the bit from the ids, so each side expects the opposite of its own. */
    @Test
    public void thetwoSidesExpectOppositeBits() throws Exception {
        SessionManager alice = new SessionManager(ALICE, id -> aliceIdentity.getPublic());
        Session session = alice.getSession(BOB);
        session.setSecretKey(sessionKey);
        session.setState(Session.State.ESTABLISHED);

        // Alice's own transmit bit is 1, so a frame arriving at Alice on 1 is her own reflected
        // back. Bob accepted 1 from Alice; Alice must not.
        byte[] nonce = iv(1, 5);
        Message reflected = MessageSigner.sign(new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId(BOB)
                .setReceiverId(ALICE)
                .setPayload(AESUtils.encrypt("echo".getBytes(StandardCharsets.UTF_8),
                        sessionKey, nonce))
                .setIv(nonce)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned(), aliceIdentity.getPrivate());

        assertEquals(SessionManager.Outcome.DROP_BAD_DIRECTION,
                alice.onMessage(reflected).outcome);
    }
}
