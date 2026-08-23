package com.e2eechat.desktop;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.ConnectionManager;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.session.Session;
import com.e2eechat.core.session.SessionManager;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class ChatClientTest {

    private ChatClient aliceClient;
    private ChatClient bobClient;
    private List<Message> aliceToBob;
    private List<Message> bobToAlice;
    private String aliceIdStr;
    private String bobIdStr;

    @org.junit.Rule
    public org.junit.rules.TemporaryFolder tmp = new org.junit.rules.TemporaryFolder();

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair aliceId = kpg.generateKeyPair();
        KeyPair bobId = kpg.generateKeyPair();

        // Ids are derived from the identity keys. A HELLO whose sender id is not the hash of the
        // key it carries is now rejected, so the test has to use real ids rather than "Alice".
        aliceIdStr = PeerId.of(aliceId.getPublic());
        bobIdStr = PeerId.of(bobId.getPublic());
        java.io.File dirA = tmp.newFolder("alice");
        java.io.File dirB = tmp.newFolder("bob");

        SecretKey dbKey = new SecretKeySpec(new byte[32], "AES");
        
        aliceToBob = new ArrayList<>();
        bobToAlice = new ArrayList<>();

        MessageRepository dummyRepoAlice = new MessageRepository(":memory:", dbKey) {
            @Override public void saveMessage(String sender, String receiver, String content, long timestamp) {}
        };
        
        MessageRepository dummyRepoBob = new MessageRepository(":memory:", dbKey) {
            @Override public void saveMessage(String sender, String receiver, String content, long timestamp) {}
        };

        IdentityKeyStore dummyStoreAlice = new IdentityKeyStore() {
            @Override public KeyPair loadOrCreateIdentity(char[] passphrase) { return aliceId; }
            @Override public void storePeerKey(String peerId, PublicKey key) {}
            @Override public Optional<PublicKey> getPeerKey(String peerId) { return Optional.of(bobId.getPublic()); }
            @Override public String fingerprint(PublicKey key) { return "bob-fp"; }
        };
        
        IdentityKeyStore dummyStoreBob = new IdentityKeyStore() {
            @Override public KeyPair loadOrCreateIdentity(char[] passphrase) { return bobId; }
            @Override public void storePeerKey(String peerId, PublicKey key) {}
            @Override public Optional<PublicKey> getPeerKey(String peerId) { return Optional.of(aliceId.getPublic()); }
            @Override public String fingerprint(PublicKey key) { return "alice-fp"; }
        };

        Function<String, PublicKey> aliceLookup = id -> bobIdStr.equals(id) ? bobId.getPublic() : null;
        Function<String, PublicKey> bobLookup = id -> aliceIdStr.equals(id) ? aliceId.getPublic() : null;

        SessionManager aliceSM = new SessionManager(aliceIdStr, aliceLookup);
        SessionManager bobSM = new SessionManager(bobIdStr, bobLookup);

        aliceClient = new ChatClient(aliceIdStr, aliceId, aliceSM, dummyRepoAlice, dummyStoreAlice, new PeerDirectory(dirA), "Alice") {
            // Mock connection manager
            @Override
            public void startSecureChat(String peerId) {
                System.out.println("aliceClient.startSecureChat called!");
                try {
                    super.startSecureChat(peerId);
                    System.out.println("aliceClient.startSecureChat SUCCESS!");
                } catch (Exception e) {
                    System.out.println("aliceClient.startSecureChat THREW: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        };
        
        // Use reflection or just a custom ConnectionManager to mock the network
        ConnectionManager mockAliceConn = new ConnectionManager("localhost", 0, aliceIdStr, aliceClient) {
            @Override public void sendMessage(Message msg) { 
                System.out.println("ALICE SENDING: " + msg.getType());
                aliceToBob.add(msg); 
                bobClient.onMessageReceived(msg); 
            }
            @Override public void start() {}
        };
        
        bobClient = new ChatClient(bobIdStr, bobId, bobSM, dummyRepoBob, dummyStoreBob, new PeerDirectory(dirB), "Bob");
        ConnectionManager mockBobConn = new ConnectionManager("localhost", 0, bobIdStr, bobClient) {
            @Override public void sendMessage(Message msg) { 
                System.out.println("BOB SENDING: " + msg.getType());
                bobToAlice.add(msg); 
                aliceClient.onMessageReceived(msg); 
            }
            @Override public void start() {}
        };
        
        // Inject via reflection since it's private and we don't have a setter
        java.lang.reflect.Field cmField = ChatClient.class.getDeclaredField("connectionManager");
        cmField.setAccessible(true);
        cmField.set(aliceClient, mockAliceConn);
        cmField.set(bobClient, mockBobConn);
        
        MessageListener dummyListener = new MessageListener() {
            @Override public void onMessageReceived(Message msg) {}
            @Override public void onConnectionStateChanged(ConnectionState state) {}
        };
        aliceClient.addMessageListener(dummyListener);
        bobClient.addMessageListener(dummyListener);
    }

    @Test
    public void testHandshakeAndEncryptedMessaging() {
        aliceClient.startSecureChat(bobIdStr);
        
        System.out.println("Before assert, aliceToBob size: " + aliceToBob.size());
        // Alice should have sent HELLO and KEY_EXCHANGE_INIT
        assertEquals(2, aliceToBob.size());
        assertEquals(MessageType.HELLO, aliceToBob.get(0).getType());
        assertEquals(MessageType.KEY_EXCHANGE_INIT, aliceToBob.get(1).getType());
        
        // Bob should have replied with KEY_EXCHANGE_REPLY
        assertEquals(1, bobToAlice.size());
        assertEquals(MessageType.KEY_EXCHANGE_REPLY, bobToAlice.get(0).getType());
        
        // Both sessions should be ESTABLISHED
        assertEquals(Session.State.ESTABLISHED, aliceClient.getSession().getState());
        bobClient.setCurrentPeerId(aliceIdStr);
        assertEquals(Session.State.ESTABLISHED, bobClient.getSession().getState());
        
        // Now send an encrypted message
        List<Message> receivedByBob = new ArrayList<>();
        bobClient.addMessageListener(new MessageListener() {
            @Override public void onMessageReceived(Message msg) {
                receivedByBob.add(msg);
            }
            @Override public void onConnectionStateChanged(ConnectionState state) {}
        });
        
        aliceClient.sendMessage("Secret message to Bob");
        
        // Assert the wire message is ciphertext
        Message wireMessage = aliceToBob.get(2);
        assertEquals(MessageType.TEXT_MESSAGE, wireMessage.getType());
        assertNotEquals("Secret message to Bob", new String(wireMessage.getPayload()));
        
        // Assert Bob received the plaintext
        assertEquals(1, receivedByBob.size());
        assertEquals("Secret message to Bob", new String(receivedByBob.get(0).getPayload()));
    }

    @Test
    public void testTamperedMessageRejected() throws Exception {
        aliceClient.startSecureChat(bobIdStr);
        
        List<Message> receivedByBob = new ArrayList<>();
        bobClient.addMessageListener(new MessageListener() {
            @Override public void onMessageReceived(Message msg) {
                receivedByBob.add(msg);
            }
            @Override public void onConnectionStateChanged(ConnectionState state) {}
        });
        
        // Intercept Alice's send
        ConnectionManager tamperedConn = new ConnectionManager("localhost", 0, aliceIdStr, aliceClient) {
            @Override public void sendMessage(Message msg) { 
                if (msg.getType() == MessageType.TEXT_MESSAGE) {
                    byte[] payload = msg.getPayload();
                    payload[0] ^= 0x01; // flip a bit
                    Message tampered = new MessageBuilder()
                            .setType(msg.getType())
                            .setSenderId(msg.getSenderId())
                            .setReceiverId(msg.getReceiverId())
                            .setPayload(payload)
                            .setIv(msg.getIv())
                            .setMessageId(msg.getMessageId())
                            .setTimestamp(msg.getTimestamp())
                            .setSignature(msg.getSignature())
                            .buildUnsigned(); // Keep original signature (which is now invalid, but even if valid, AEAD fails)
                    bobClient.onMessageReceived(tampered);
                } else {
                    bobClient.onMessageReceived(msg);
                }
            }
            @Override public void start() {}
        };
        java.lang.reflect.Field cmField = ChatClient.class.getDeclaredField("connectionManager");
        cmField.setAccessible(true);
        cmField.set(aliceClient, tamperedConn);
        
        aliceClient.sendMessage("This should be dropped");
        
        // Bob's listener should NOT have received the message
        assertEquals(0, receivedByBob.size());
    }
}
