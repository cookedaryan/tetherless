package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.ConnectionManager;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.session.Session;
import com.e2eechat.core.session.SessionManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The send and receive pipeline, over a fake transport.
 *
 * <p>Two clients are wired directly to each other in place of the relay, so every frame that would
 * have crossed the network is captured and can be asserted on. That is the point of the fixture:
 * what leaves a client has to be ciphertext, and a frame that was altered in flight has to be
 * dropped rather than rendered.
 *
 * <p>Swing is deliberately absent. The pipeline lives in {@link ChatClient}, and testing it does
 * not need a window.
 */
public class ChatClientTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ChatClient aliceClient;
    private ChatClient bobClient;

    /** Every frame Alice put on the wire, in order. */
    private final List<Message> aliceToBob = new ArrayList<>();

    /** Every frame Bob put on the wire, in order. */
    private final List<Message> bobToAlice = new ArrayList<>();

    /** Text of every message Alice's repository was asked to store. */
    private final List<String> aliceSaved = new ArrayList<>();

    private String aliceId;
    private String bobId;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair aliceKeys = generator.generateKeyPair();
        KeyPair bobKeys = generator.generateKeyPair();

        // Ids are derived from the identity keys. A HELLO whose sender id is not the hash of the
        // key it carries is rejected, so the fixture has to use real ids rather than "Alice".
        aliceId = PeerId.of(aliceKeys.getPublic());
        bobId = PeerId.of(bobKeys.getPublic());

        File aliceDir = tmp.newFolder("alice");
        File bobDir = tmp.newFolder("bob");
        SecretKey dbKey = new SecretKeySpec(new byte[32], "AES");

        Function<String, PublicKey> aliceLookup =
            id -> bobId.equals(id) ? bobKeys.getPublic() : null;
        Function<String, PublicKey> bobLookup =
            id -> aliceId.equals(id) ? aliceKeys.getPublic() : null;

        aliceClient = new ChatClient(aliceId, aliceKeys,
                new SessionManager(aliceId, aliceLookup),
                recordingRepository(dbKey, aliceSaved), keyStore(aliceKeys, bobKeys.getPublic()),
                new PeerDirectory(aliceDir), "Alice");
        bobClient = new ChatClient(bobId, bobKeys,
                new SessionManager(bobId, bobLookup),
                discardingRepository(dbKey), keyStore(bobKeys, aliceKeys.getPublic()),
                new PeerDirectory(bobDir), "Bob");

        injectTransport(aliceClient, aliceToBob, bobClient);
        injectTransport(bobClient, bobToAlice, aliceClient);

        aliceClient.addMessageListener(silentListener());
        bobClient.addMessageListener(silentListener());
    }

    /** Persistence is not what these tests are about; MessageRepositoryTest covers it. */
    private static MessageRepository discardingRepository(SecretKey dbKey) {
        return new MessageRepository(":memory:", dbKey) {
            @Override
            public void saveMessage(String sender, String receiver, String content, long timestamp) {
                // Discarded.
            }
        };
    }

    /** Records what was stored, so a test can tell a message that went from one that did not. */
    private static MessageRepository recordingRepository(SecretKey dbKey, List<String> saved) {
        return new MessageRepository(":memory:", dbKey) {
            @Override
            public void saveMessage(String sender, String receiver, String content, long timestamp) {
                saved.add(content);
            }

            @Override
            public void saveMessage(String messageId, String sender, String receiver, String content,
                                    long timestamp, ChatMessage.Status status, String replyToId,
                                    String replyToSender, String replyToPreview, boolean markRead) {
                saved.add(content);
            }
        };
    }

    /**
     * Spends the session's whole send budget. Each call is a counter increment, not a message, so
     * this costs milliseconds rather than a hundred thousand signatures.
     */
    private static void spendSendBudget(ChatClient client, String peerId) {
        client.setCurrentPeerId(peerId);
        Session session = client.getSession();
        for (long i = 0; i < Session.MAX_SENDS_PER_KEY; i++) {
            session.getNextSendCounter();
        }
    }

    private static IdentityKeyStore keyStore(KeyPair own, PublicKey peer) {
        return new IdentityKeyStore() {
            @Override
            public KeyPair loadOrCreateIdentity(char[] passphrase) {
                return own;
            }

            @Override
            public void storePeerKey(String peerId, PublicKey key) {
                // Nothing to persist in a test.
            }

            @Override
            public Optional<PublicKey> getPeerKey(String peerId) {
                return Optional.of(peer);
            }

            @Override
            public String fingerprint(PublicKey key) {
                return "fingerprint";
            }
        };
    }

    /** Replaces the client's transport with one that records frames and hands them to the peer. */
    private static void injectTransport(ChatClient client, List<Message> wire, ChatClient peer)
            throws Exception {
        ConnectionManager transport =
                new ConnectionManager("localhost", 0, "unused", client) {
                    @Override
                    public void sendMessage(Message message) {
                        wire.add(message);
                        peer.onMessageReceived(message);
                    }

                    @Override
                    public void start() {
                        // No socket: this fixture is the network.
                    }
                };
        Field field = ChatClient.class.getDeclaredField("connectionManager");
        field.setAccessible(true);
        field.set(client, transport);
    }

    private static MessageListener silentListener() {
        return new MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                // Ignored.
            }

            @Override
            public void onConnectionStateChanged(ConnectionState state) {
                // Ignored.
            }
        };
    }

    /** Collects what a client actually surfaced to its UI. */
    private static List<Message> recordDeliveries(ChatClient client) {
        List<Message> delivered = new ArrayList<>();
        client.addMessageListener(new MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                delivered.add(message);
            }

            @Override
            public void onConnectionStateChanged(ConnectionState state) {
                // Ignored.
            }
        });
        return delivered;
    }

    @Test
    public void theHandshakeEstablishesASessionAtBothEnds() {
        aliceClient.startSecureChat(bobId);

        assertEquals(2, aliceToBob.size());
        assertEquals(MessageType.HELLO, aliceToBob.get(0).getType());
        assertEquals(MessageType.KEY_EXCHANGE_INIT, aliceToBob.get(1).getType());

        assertEquals(1, bobToAlice.size());
        assertEquals(MessageType.KEY_EXCHANGE_REPLY, bobToAlice.get(0).getType());

        assertEquals(Session.State.ESTABLISHED, aliceClient.getSession().getState());
        bobClient.setCurrentPeerId(aliceId);
        assertEquals(Session.State.ESTABLISHED, bobClient.getSession().getState());
    }

    @Test
    public void whatLeavesTheClientIsCiphertextAndWhatArrivesIsPlaintext() {
        aliceClient.startSecureChat(bobId);
        List<Message> deliveredToBob = recordDeliveries(bobClient);

        aliceClient.sendMessage("Secret message to Bob");

        Message onTheWire = aliceToBob.get(2);
        assertEquals(MessageType.TEXT_MESSAGE, onTheWire.getType());
        String wireBytes = new String(onTheWire.getPayload(), StandardCharsets.UTF_8);
        assertNotEquals("Secret message to Bob", wireBytes);
        assertFalse("plaintext leaked onto the wire", wireBytes.contains("Secret"));

        assertEquals(1, deliveredToBob.size());
        assertEquals("Secret message to Bob",
                new String(deliveredToBob.get(0).getPayload(), StandardCharsets.UTF_8));
    }

    /** Ten messages, so a session that only encrypts the first one cannot pass. */
    @Test
    public void everyMessageInASessionIsEncrypted() {
        aliceClient.startSecureChat(bobId);
        List<Message> deliveredToBob = recordDeliveries(bobClient);

        for (int i = 0; i < 10; i++) {
            aliceClient.sendMessage("plaintext number " + i);
        }

        assertEquals(10, deliveredToBob.size());
        for (Message frame : aliceToBob) {
            if (frame.getType() != MessageType.TEXT_MESSAGE) {
                continue;
            }
            String wireBytes = new String(frame.getPayload(), StandardCharsets.UTF_8);
            assertFalse("plaintext leaked onto the wire: " + wireBytes,
                    wireBytes.contains("plaintext number"));
        }
        for (int i = 0; i < 10; i++) {
            assertEquals("plaintext number " + i,
                    new String(deliveredToBob.get(i).getPayload(), StandardCharsets.UTF_8));
        }
    }

    /** The same text twice must not produce the same frame, or the relay can spot repeats. */
    @Test
    public void identicalTextProducesDifferentCiphertext() {
        aliceClient.startSecureChat(bobId);

        aliceClient.sendMessage("the same words");
        aliceClient.sendMessage("the same words");

        byte[] first = aliceToBob.get(2).getPayload();
        byte[] second = aliceToBob.get(3).getPayload();
        assertNotEquals(new String(first, StandardCharsets.UTF_8),
                new String(second, StandardCharsets.UTF_8));
    }

    /**
     * A key that has spent its send budget is renewed rather than being the end of the
     * conversation.
     *
     * <p>Reaching the budget used to throw, be logged, and return no message id - and because a
     * renewal did not reset the counter either, the peer stayed unreachable for the life of the
     * process. Now the client negotiates a fresh key and tells the window the session is being
     * re-established, so it recovers on its own.
     */
    @Test
    public void aSpentSendBudgetRenewsTheKeyInsteadOfEndingTheConversation() {
        aliceClient.startSecureChat(bobId);
        spendSendBudget(aliceClient, bobId);
        aliceToBob.clear();

        String messageId = aliceClient.sendMessage("this one does not go", null);

        assertNull("a message was reported sent under a spent key", messageId);
        for (Message frame : aliceToBob) {
            assertNotEquals("a text message went out under a spent key",
                    MessageType.TEXT_MESSAGE, frame.getType());
        }
        assertTrue("no handshake was started to renew the key", aliceToBob.size() >= 2);
        assertEquals(MessageType.HELLO, aliceToBob.get(0).getType());
        assertEquals(MessageType.KEY_EXCHANGE_INIT, aliceToBob.get(1).getType());
    }

    /** The renewal completes by itself, and sending works again afterwards. */
    @Test
    public void sendingWorksAgainOnceTheRenewalCompletes() {
        aliceClient.startSecureChat(bobId);
        spendSendBudget(aliceClient, bobId);

        assertNull(aliceClient.sendMessage("this one does not go", null));

        // The fake transport hands frames straight to Bob, so by now Bob has answered and Alice
        // has adopted the new key.
        assertEquals(Session.State.ESTABLISHED, aliceClient.getSession().getState());

        List<Message> deliveredToBob = recordDeliveries(bobClient);
        assertNotNull("sending did not recover after the renewal",
                aliceClient.sendMessage("this one does", null));
        assertEquals(1, deliveredToBob.size());
        assertEquals("this one does",
                new String(deliveredToBob.get(0).getPayload(), StandardCharsets.UTF_8));
    }

    /**
     * Nothing is written until the message has actually been encrypted.
     *
     * <p>The row used to be saved first, so a send that then failed left history claiming a message
     * had been sent when nothing ever left the machine.
     */
    @Test
    public void aMessageThatCannotBeEncryptedIsNotRecordedAsSent() {
        aliceClient.startSecureChat(bobId);
        spendSendBudget(aliceClient, bobId);
        aliceSaved.clear();

        assertNull(aliceClient.sendMessage("never encrypted", null));

        assertTrue("a message that never went out was recorded as sent: " + aliceSaved,
                aliceSaved.isEmpty());
    }

    @Test
    public void aMessageThatGoesOutIsRecorded() {
        aliceClient.startSecureChat(bobId);
        aliceSaved.clear();

        assertNotNull(aliceClient.sendMessage("this one goes", null));

        assertEquals(1, aliceSaved.size());
        assertEquals("this one goes", aliceSaved.get(0));
    }

    @Test
    public void aFrameAlteredInFlightIsDroppedRatherThanRendered() throws Exception {
        aliceClient.startSecureChat(bobId);
        List<Message> deliveredToBob = recordDeliveries(bobClient);

        // A relay that flips one bit of the ciphertext. The signature is carried over unchanged,
        // so this fails authentication whichever check fires first.
        Field field = ChatClient.class.getDeclaredField("connectionManager");
        field.setAccessible(true);
        field.set(aliceClient, new ConnectionManager("localhost", 0, "unused", aliceClient) {
            @Override
            public void sendMessage(Message message) {
                if (message.getType() != MessageType.TEXT_MESSAGE) {
                    bobClient.onMessageReceived(message);
                    return;
                }
                byte[] payload = message.getPayload();
                payload[0] ^= 0x01;
                bobClient.onMessageReceived(new MessageBuilder()
                        .setType(message.getType())
                        .setSenderId(message.getSenderId())
                        .setReceiverId(message.getReceiverId())
                        .setPayload(payload)
                        .setIv(message.getIv())
                        .setMessageId(message.getMessageId())
                        .setTimestamp(message.getTimestamp())
                        .setSignature(message.getSignature())
                        .buildUnsigned());
            }

            @Override
            public void start() {
                // No socket: this fixture is the network.
            }
        });

        aliceClient.sendMessage("This should be dropped");

        assertTrue("a tampered frame reached the user", deliveredToBob.isEmpty());
    }
}
