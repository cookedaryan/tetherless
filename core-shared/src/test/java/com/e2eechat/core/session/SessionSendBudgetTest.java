package com.e2eechat.core.session;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;

import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The limit on how many messages one key may encrypt, and what happens on reaching it.
 *
 * <p>The limit itself is not the interesting part. What matters is that reaching it used to end the
 * conversation permanently and silently: the counter threw, the caller logged it and returned no
 * message id, the window dropped the text on the floor, and because renewing a key did not reset
 * the counter there was nothing the user could do about it short of restarting the application.
 */
public class SessionSendBudgetTest {

    private static KeyPair aliceKeys;
    private static KeyPair bobKeys;
    private static String aliceId;
    private static String bobId;

    private Session session;
    private SessionManager sessions;

    private static SecretKey key(byte seed) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, seed);
        return new SecretKeySpec(bytes, "AES");
    }

    @Before
    public void setUp() {
        session = new Session("peer");
        session.setSecretKey(key((byte) 1));
    }

    /** Spends the whole budget. Only a counter increment per call, so this is quick. */
    private static void spendBudget(Session session) {
        for (long i = 0; i < Session.MAX_SENDS_PER_KEY; i++) {
            session.getNextSendCounter();
        }
    }

    // -------------------------------------------------------------- the budget

    @Test
    public void aFreshKeyHasItsWholeBudget() {
        assertFalse(session.isSendBudgetExhausted());
    }

    @Test
    public void theBudgetIsNotExhaustedUntilItIsSpent() {
        for (long i = 0; i < Session.MAX_SENDS_PER_KEY - 1; i++) {
            session.getNextSendCounter();
        }

        assertFalse("the budget was reported spent one message early",
                session.isSendBudgetExhausted());
    }

    @Test
    public void theBudgetIsExhaustedOnceSpent() {
        spendBudget(session);

        assertTrue(session.isSendBudgetExhausted());
    }

    /** The guard stays, so nothing can quietly reuse a nonce by ignoring the budget. */
    @Test
    public void takingACounterBeyondTheBudgetIsRefused() {
        spendBudget(session);

        try {
            session.getNextSendCounter();
            fail("a counter was issued beyond the budget");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * The whole point of renewing. Before, the counter carried across a new key, so a session that
     * had spent its budget stayed spent and the "rekey required" the error asked for could not be
     * done.
     */
    @Test
    public void renewingTheKeyRestoresTheBudget() {
        spendBudget(session);
        assertTrue(session.isSendBudgetExhausted());

        session.setSecretKey(key((byte) 2));

        assertFalse("renewing the key did not restore the send budget",
                session.isSendBudgetExhausted());
        assertEquals(1, session.getNextSendCounter());
    }

    // ------------------------------------------------------- reaching it in use

    @Test
    public void aSpentBudgetMakesEncryptStartAFreshHandshakeInsteadOfFailing() throws Exception {
        List<Message> sent = new ArrayList<>();
        SecureChat alice = secureChat(sent);
        Session live = establishedSession();
        spendBudget(live);
        sent.clear();

        try {
            alice.encrypt(bobId, "message-id", "anything".getBytes(StandardCharsets.UTF_8));
            fail("a message was encrypted with a spent key");
        } catch (SessionRenewalRequiredException expected) {
            assertEquals(bobId, expected.getPeerId());
        }

        assertTrue("no handshake was started to renew the key", sent.size() >= 2);
        assertEquals(MessageType.HELLO, sent.get(0).getType());
        assertEquals(MessageType.KEY_EXCHANGE_INIT, sent.get(1).getType());
        assertEquals(Session.State.HANDSHAKE_SENT, alice.stateOf(bobId));
    }

    /** A renewal already under way must not start another on every attempt. */
    @Test
    public void aSecondAttemptDuringRenewalDoesNotStartAnotherHandshake() throws Exception {
        List<Message> sent = new ArrayList<>();
        SecureChat alice = secureChat(sent);
        Session live = establishedSession();
        spendBudget(live);

        try {
            alice.encrypt(bobId, "one", "anything".getBytes(StandardCharsets.UTF_8));
            fail("expected a renewal");
        } catch (SessionRenewalRequiredException expected) {
            assertNotNull(expected.getMessage());
        }
        int afterFirst = sent.size();

        try {
            alice.encrypt(bobId, "two", "anything".getBytes(StandardCharsets.UTF_8));
            fail("the session is not established, so this must not encrypt");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }

        assertEquals("a second handshake was started while one was already in flight",
                afterFirst, sent.size());
    }

    @Test
    public void encryptingUnderAFreshBudgetIsUntouched() throws Exception {
        List<Message> sent = new ArrayList<>();
        SecureChat alice = secureChat(sent);
        establishedSession();

        Message encrypted =
                alice.encrypt(bobId, "message-id", "hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(MessageType.TEXT_MESSAGE, encrypted.getType());
        assertNotNull(encrypted.getIv());
    }

    // ------------------------------------------------------------------ fixture

    private static synchronized void keys() throws Exception {
        if (aliceKeys != null) {
            return;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        aliceKeys = generator.generateKeyPair();
        bobKeys = generator.generateKeyPair();
        aliceId = PeerId.of(aliceKeys.getPublic());
        bobId = PeerId.of(bobKeys.getPublic());
    }

    private SecureChat secureChat(List<Message> sent) throws Exception {
        keys();
        sessions = new SessionManager(aliceId,
            id -> bobId.equals(id) ? bobKeys.getPublic() : null);
        return new SecureChat(aliceId, aliceKeys, sessions, keyStore(), sent::add, null, "Alice");
    }

    /**
     * Puts the session straight into the state a completed handshake would leave it in, so a test
     * about the send budget does not have to run one.
     */
    private Session establishedSession() {
        Session live = sessions.getSession(bobId);
        live.setSecretKey(key((byte) 7));
        return live;
    }

    private static IdentityKeyStore keyStore() {
        return new IdentityKeyStore() {
            @Override
            public KeyPair loadOrCreateIdentity(char[] passphrase) {
                return aliceKeys;
            }

            @Override
            public void storePeerKey(String peerId, PublicKey key) {
                // Nothing to persist in a test.
            }

            @Override
            public Optional<PublicKey> getPeerKey(String peerId) {
                return Optional.of(bobKeys.getPublic());
            }

            @Override
            public String fingerprint(PublicKey key) {
                return "fingerprint";
            }
        };
    }
}
