package com.e2eechat.server;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.protocol.MessageSigner;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * What the relay demands before it hands out an id.
 *
 * <p>Registration was previously taken on trust: the relay read {@code senderId} off an unsigned
 * frame and registered it. A peer id is public - it is what you give people so they can message you
 * - so anyone could connect as anyone, and the real owner would be refused with {@code ID_TAKEN}
 * for as long as the squatter held the socket.
 */
public class RegistrationAuthenticatorTest {

    private static KeyPair alice;
    private static KeyPair mallory;
    private static String aliceId;

    private RegistrationAuthenticator authenticator;

    @BeforeClass
    public static void generateIdentities() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        alice = generator.generateKeyPair();
        mallory = generator.generateKeyPair();
        aliceId = PeerId.of(alice.getPublic());
    }

    @Before
    public void setUp() {
        authenticator = new RegistrationAuthenticator();
    }

    private static MessageBuilder hello(String senderId, java.security.PublicKey key)
            throws Exception {
        return new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(senderId)
                .setPayload(HelloPayload.encode(key, ""))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis());
    }

    @Test
    public void aProperlySignedRegistrationIsAccepted() throws Exception {
        Message registration = MessageSigner.sign(
                hello(aliceId, alice.getPublic()).buildUnsigned(), alice.getPrivate());

        assertNull(authenticator.reject(registration));
    }

    /** The squatting attack: claim the id, sign with a key that is not the one it came from. */
    @Test
    public void claimingSomebodyElsesIdWithYourOwnKeyIsRefused() throws Exception {
        Message registration = MessageSigner.sign(
                hello(aliceId, mallory.getPublic()).buildUnsigned(), mallory.getPrivate());

        assertEquals("ID_DOES_NOT_MATCH_KEY", authenticator.reject(registration));
    }

    /** Presenting Alice's key without her private key: the id matches, the signature cannot. */
    @Test
    public void presentingSomebodyElsesPublicKeyIsRefused() throws Exception {
        Message registration = MessageSigner.sign(
                hello(aliceId, alice.getPublic()).buildUnsigned(), mallory.getPrivate());

        assertEquals("BAD_SIGNATURE", authenticator.reject(registration));
    }

    @Test
    public void anUnsignedRegistrationIsRefused() throws Exception {
        assertEquals("BAD_SIGNATURE",
                authenticator.reject(hello(aliceId, alice.getPublic()).buildUnsigned()));
    }

    @Test
    public void aRegistrationCarryingNoKeyIsRefused() {
        Message registration = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(aliceId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();

        assertEquals("NO_IDENTITY_KEY", authenticator.reject(registration));
    }

    @Test
    public void anIdThatIsNotAPeerIdIsRefused() throws Exception {
        Message registration = MessageSigner.sign(
                hello("alice", alice.getPublic()).buildUnsigned(), alice.getPrivate());

        assertEquals("BAD_PEER_ID", authenticator.reject(registration));
    }

    /** Freshness: a frame from outside the window is refused even though it is validly signed. */
    @Test
    public void aStaleRegistrationIsRefused() throws Exception {
        Message stale = MessageSigner.sign(
                hello(aliceId, alice.getPublic())
                        .setTimestamp(System.currentTimeMillis()
                                - RegistrationAuthenticator.MAX_SKEW_MS - 1000)
                        .buildUnsigned(),
                alice.getPrivate());

        assertEquals("STALE_REGISTRATION", authenticator.reject(stale));
    }

    /**
     * A captured registration cannot be presented twice. Freshness alone would leave a window in
     * which one could be replayed, here or at another relay - the signature says nothing about
     * which relay it was meant for.
     */
    @Test
    public void areplayedRegistrationIsRefused() throws Exception {
        Message registration = MessageSigner.sign(
                hello(aliceId, alice.getPublic()).buildUnsigned(), alice.getPrivate());

        assertNull(authenticator.reject(registration));
        assertEquals("REPLAYED_REGISTRATION", authenticator.reject(registration));
    }

    /** Alice reconnecting is not a replay: each registration is a fresh frame. */
    @Test
    public void reconnectingWithAFreshRegistrationIsAccepted() throws Exception {
        assertNull(authenticator.reject(MessageSigner.sign(
                hello(aliceId, alice.getPublic()).buildUnsigned(), alice.getPrivate())));
        assertNull(authenticator.reject(MessageSigner.sign(
                hello(aliceId, alice.getPublic()).buildUnsigned(), alice.getPrivate())));
    }

    @Test
    public void theAuthenticatorNamesEveryRefusal() throws Exception {
        // A guard against a refusal path that returns null and silently admits the claim.
        assertNotNull(authenticator.reject(new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(null)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned()));
    }
}
