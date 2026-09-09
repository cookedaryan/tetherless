package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.ConnectionManager;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.protocol.MessageSigner;
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
import java.util.UUID;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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

    /** Status of every message Alice's repository was asked to store, in order. */
    private final List<String> savedStatuses = new ArrayList<>();

    /** Every status advance Alice's repository was asked to make, as "id=STATUS". */
    private final List<String> statusUpdates = new ArrayList<>();

    /** Alice's outbox: messages written down but not yet handed to the transport. */
    private final List<ChatMessage> aliceOutbox = new ArrayList<>();

    private String aliceId;
    private String bobId;

    /** Kept as fields so a test can forge a frame that is genuinely signed by the right peer. */
    private KeyPair aliceKeys;
    private KeyPair bobKeys;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        aliceKeys = generator.generateKeyPair();
        bobKeys = generator.generateKeyPair();

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
                recordingRepository(dbKey, aliceSaved, savedStatuses, statusUpdates, aliceOutbox),
                keyStore(aliceKeys, bobKeys.getPublic()),
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

    /**
     * Records what was stored, so a test can tell a message that went from one that did not.
     *
     * <p>It also keeps a real outbox: rows saved as {@code PENDING} are held until something
     * advances them, which is what lets a test watch the queue actually drain rather than only
     * watch it fill.
     */
    private static MessageRepository recordingRepository(SecretKey dbKey, List<String> saved,
                                                          List<String> statuses,
                                                          List<String> statusUpdates,
                                                          List<ChatMessage> outbox) {
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
                statuses.add(status.name());
                if (status == ChatMessage.Status.PENDING) {
                    outbox.add(new ChatMessage(messageId, sender, receiver, content, timestamp,
                            status, replyToId, replyToSender, replyToPreview));
                }
            }

            @Override
            public void updateStatus(String messageId, ChatMessage.Status status) {
                statusUpdates.add(messageId + "=" + status.name());
                if (status != ChatMessage.Status.PENDING) {
                    outbox.removeIf(m -> m.getMessageId().equals(messageId));
                }
            }

            @Override
            public List<ChatMessage> getPending(String self, String peerId) {
                List<ChatMessage> queued = new ArrayList<>();
                for (ChatMessage m : outbox) {
                    if (m.getSender().equals(self) && m.getReceiver().equals(peerId)) {
                        queued.add(m);
                    }
                }
                return queued;
            }

            @Override
            public List<String> getPendingPeers(String self) {
                List<String> peers = new ArrayList<>();
                for (ChatMessage m : outbox) {
                    if (m.getSender().equals(self) && !peers.contains(m.getReceiver())) {
                        peers.add(m.getReceiver());
                    }
                }
                return peers;
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

    /**
     * A keypair for the stand-in transports below. They never open a socket and never register,
     * so the key is only there to satisfy the constructor; it is generated once because RSA-2048
     * keygen is not cheap enough to repeat per fixture.
     */
    private static KeyPair fixtureKeys() throws Exception {
        if (fixtureKeys == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            fixtureKeys = generator.generateKeyPair();
        }
        return fixtureKeys;
    }

    private static KeyPair fixtureKeys;

    /**
     * A frame with no payload must be ignored, not fatal.
     *
     * <p>{@code MessageCodec} encodes a null payload as a length of -1 and decodes it back to
     * null, so this is a legal frame - and reading it as text on the receive path threw a
     * {@link NullPointerException}. That runs on the reader thread, where the only handler is the
     * read loop's catch-all: it logged, gave up, and closed the socket. One 20-byte frame from a
     * peer or a hostile relay was enough to knock a client offline, repeatedly.
     *
     * <p>The acknowledgement is genuinely signed by Bob, because after the control-frame signature
     * fix an unsigned one is dropped earlier and would never reach the code under test.
     */
    @Test
    public void anAcknowledgementWithNoPayloadIsIgnoredRatherThanFatal() throws Exception {
        Message emptyAck = MessageSigner.sign(new MessageBuilder()
                .setType(MessageType.DELIVERY_ACK)
                .setSenderId(bobId)
                .setReceiverId(aliceId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned(), bobKeys.getPrivate());

        aliceClient.onMessageReceived(emptyAck);

        assertTrue("an acknowledgement naming no message must not touch any status: "
                + statusUpdates, statusUpdates.isEmpty());
    }

    /** The same frame carrying a message id still does what it is supposed to. */
    @Test
    public void anAcknowledgementNamingAMessageStillAdvancesIt() throws Exception {
        Message ack = MessageSigner.sign(new MessageBuilder()
                .setType(MessageType.DELIVERY_ACK)
                .setSenderId(bobId)
                .setReceiverId(aliceId)
                .setPayload("m1".getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned(), bobKeys.getPrivate());

        aliceClient.onMessageReceived(ack);

        assertTrue("expected m1 to be advanced to DELIVERED, saw " + statusUpdates,
                statusUpdates.contains("m1=DELIVERED"));
    }

    /** Replaces the client's transport with one that records frames and hands them to the peer. */
    private static void injectTransport(ChatClient client, List<Message> wire, ChatClient peer)
            throws Exception {
        ConnectionManager transport =
                new ConnectionManager("localhost", 0, "unused", fixtureKeys(), client) {
                    @Override
                    public boolean sendMessage(Message message) {
                        wire.add(message);
                        peer.onMessageReceived(message);
                        return true;
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

        ChatMessage sentMessage = aliceClient.sendMessage("this one does not go", null);
        String messageId = sentMessage == null ? null : sentMessage.getMessageId();

        assertNotNull("the message should be queued, not discarded", messageId);
        assertTrue("no handshake was started to renew the key", aliceToBob.size() >= 2);
        assertEquals(MessageType.HELLO, aliceToBob.get(0).getType());
        assertEquals(MessageType.KEY_EXCHANGE_INIT, aliceToBob.get(1).getType());

        // Text may follow, but only once the new key is negotiated: the queue drains after the
        // renewal. What must never happen is a message going out ahead of that handshake, which
        // would mean it was encrypted under the exhausted key.
        for (int i = 0; i < aliceToBob.size(); i++) {
            if (aliceToBob.get(i).getType() == MessageType.KEY_EXCHANGE_INIT) {
                break;
            }
            assertNotEquals("a text message went out under a spent key",
                    MessageType.TEXT_MESSAGE, aliceToBob.get(i).getType());
        }
    }

    /**
     * The renewal completes by itself, and what was typed during it is delivered rather than lost.
     *
     * <p>The message sent under the spent key used to be discarded outright. It is now queued, and
     * the session coming back drains the queue, so the user does not have to retype it.
     */
    @Test
    public void aMessageTypedDuringARenewalIsDeliveredWhenItCompletes() {
        List<Message> deliveredToBob = recordDeliveries(bobClient);
        aliceClient.startSecureChat(bobId);
        spendSendBudget(aliceClient, bobId);

        assertNotNull(aliceClient.sendMessage("typed during the renewal", null));

        // The fake transport hands frames straight to Bob, so by now Bob has answered, Alice has
        // adopted the new key, and the queue has drained on the back of that.
        assertEquals(Session.State.ESTABLISHED, aliceClient.getSession().getState());
        assertEquals("the queued message never reached Bob", 1, deliveredToBob.size());
        assertEquals("typed during the renewal",
                new String(deliveredToBob.get(0).getPayload(), StandardCharsets.UTF_8));

        assertNotNull("sending did not recover after the renewal",
                aliceClient.sendMessage("this one does", null));
        assertEquals(2, deliveredToBob.size());
    }

    /**
     * A message that could not be encrypted is never recorded as sent.
     *
     * <p>The row used to be saved first, so a send that then failed left history claiming a message
     * had been sent when nothing ever left the machine. It is now written as PENDING - recorded so
     * it is not lost, but never as SENT until the transport has actually taken it.
     */
    @Test
    public void aMessageThatCannotBeEncryptedIsNotRecordedAsSent() {
        aliceClient.startSecureChat(bobId);
        spendSendBudget(aliceClient, bobId);
        savedStatuses.clear();

        assertNotNull(aliceClient.sendMessage("never encrypted", null));

        assertFalse("nothing was written down at all", savedStatuses.isEmpty());
        assertFalse("a message that never went out was recorded as sent",
                savedStatuses.contains("SENT"));
        assertEquals("PENDING", savedStatuses.get(0));
    }

    @Test
    public void aMessageThatGoesOutIsRecorded() {
        aliceClient.startSecureChat(bobId);
        aliceSaved.clear();

        assertNotNull(aliceClient.sendMessage("this one goes", null));

        assertEquals(1, aliceSaved.size());
        assertEquals("this one goes", aliceSaved.get(0));
    }

    /**
     * A send the transport refuses is queued rather than abandoned.
     *
     * <p>It used to be recorded FAILED, which is a dead end: the transport being down is the most
     * ordinary reason a send does not go, and it is precisely the case retrying fixes.
     */
    @Test
    public void aSendTheTransportRefusesIsQueuedForRetry() throws Exception {
        aliceClient.startSecureChat(bobId);
        refuseAliceTransport();
        aliceSaved.clear();
        savedStatuses.clear();

        ChatMessage sentMessage = aliceClient.sendMessage("this one does not leave", null);
        String messageId = sentMessage == null ? null : sentMessage.getMessageId();

        assertNotNull("the message should still get an id and a bubble", messageId);
        assertEquals(1, savedStatuses.size());
        assertEquals("PENDING", savedStatuses.get(0));
    }

    /**
     * Writing to a peer there is no session with queues the message and starts the handshake.
     *
     * <p>This is the case that used to lose text outright: sendMessage returned null, no row was
     * written, and what had been typed was simply gone from the window it was typed into.
     */
    @Test
    public void aMessageToAPeerWithNoSessionIsQueuedRatherThanLost() {
        aliceClient.setCurrentPeerId(bobId);
        aliceSaved.clear();
        savedStatuses.clear();

        ChatMessage sentMessage = aliceClient.sendMessage("nobody has shaken hands yet", null);
        String messageId = sentMessage == null ? null : sentMessage.getMessageId();

        assertNotNull("the message was dropped instead of queued", messageId);
        assertEquals(1, aliceSaved.size());
        assertEquals("nobody has shaken hands yet", aliceSaved.get(0));
        assertEquals("PENDING", savedStatuses.get(0));
    }

    /** The queue drains in the order it was filled, once the session comes up. */
    @Test
    public void aQueueDrainsInOrderWhenTheSessionIsEstablished() throws Exception {
        aliceClient.startSecureChat(bobId);
        refuseAliceTransport();
        aliceClient.sendMessage("first", null);
        aliceClient.sendMessage("second", null);
        assertEquals(2, aliceOutbox.size());

        List<Message> deliveredToBob = recordDeliveries(bobClient);
        restoreAliceTransport();
        aliceClient.onConnectionStateChanged(ConnectionState.CONNECTED);

        assertEquals("the queue did not drain", 2, deliveredToBob.size());
        assertEquals("first",
                new String(deliveredToBob.get(0).getPayload(), StandardCharsets.UTF_8));
        assertEquals("second",
                new String(deliveredToBob.get(1).getPayload(), StandardCharsets.UTF_8));
        assertTrue("the outbox was not emptied", aliceOutbox.isEmpty());
    }

    @Test
    public void aSendTheTransportAcceptsIsRecordedAsSent() {
        aliceClient.startSecureChat(bobId);
        savedStatuses.clear();

        assertNotNull(aliceClient.sendMessage("this one leaves", null));

        assertEquals(1, savedStatuses.size());
        assertEquals("SENT", savedStatuses.get(0));
    }

    /**
     * A delivery acknowledgement advances the stored row, not only the bubble.
     *
     * <p>The fake transport hands Alice's message straight to Bob, whose client acknowledges it
     * automatically, so this exercises the real round trip rather than a synthesised frame.
     */
    @Test
    public void aDeliveryAcknowledgementIsPersisted() {
        aliceClient.startSecureChat(bobId);
        statusUpdates.clear();

        ChatMessage sentMessage = aliceClient.sendMessage("did this arrive", null);
        String messageId = sentMessage == null ? null : sentMessage.getMessageId();

        assertNotNull(messageId);
        assertEquals(1, statusUpdates.size());
        assertEquals(messageId + "=DELIVERED", statusUpdates.get(0));
    }

    /** Puts Alice back on the fixture's transport, as a reconnect would. */
    private void restoreAliceTransport() throws Exception {
        injectTransport(aliceClient, aliceToBob, bobClient);
    }

    /** Swaps Alice's transport for one that refuses everything, as a closed connection would. */
    private void refuseAliceTransport() throws Exception {
        ConnectionManager refusing = new ConnectionManager("localhost", 0, "unused", fixtureKeys(), aliceClient) {
            @Override
            public boolean sendMessage(Message message) {
                return false;
            }

            @Override
            public void start() {
                // No socket: this fixture is the network.
            }
        };
        Field field = ChatClient.class.getDeclaredField("connectionManager");
        field.setAccessible(true);
        field.set(aliceClient, refusing);
    }

    @Test
    public void aFrameAlteredInFlightIsDroppedRatherThanRendered() throws Exception {
        aliceClient.startSecureChat(bobId);
        List<Message> deliveredToBob = recordDeliveries(bobClient);

        // A relay that flips one bit of the ciphertext. The signature is carried over unchanged,
        // so this fails authentication whichever check fires first.
        Field field = ChatClient.class.getDeclaredField("connectionManager");
        field.setAccessible(true);
        field.set(aliceClient, new ConnectionManager("localhost", 0, "unused", fixtureKeys(), aliceClient) {
            @Override
            public boolean sendMessage(Message message) {
                if (message.getType() != MessageType.TEXT_MESSAGE) {
                    bobClient.onMessageReceived(message);
                    return true;
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
                return true;
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
