package com.e2eechat.desktop.integration;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.JceKeyStoreManager;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.session.Session;
import com.e2eechat.core.session.SessionManager;
import com.e2eechat.desktop.ChatClient;
import com.e2eechat.desktop.DatabaseHelper;
import com.e2eechat.desktop.MessageRepository;
import com.e2eechat.desktop.PeerDirectory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A complete client with no user interface.
 *
 * <p>Wired exactly as {@code Main} wires the real one - the same key store, session manager,
 * repository and {@link ChatClient} - with the window replaced by a listener that records what
 * would have been rendered. Nothing here is a stand-in for a production class; if this connects
 * and talks, the shipped client's core does too.
 */
final class HeadlessClient implements AutoCloseable {

    /** The identity passphrase. This keystore lives in a temporary directory for one test run. */
    private static final char[] PASSPHRASE = "integration-harness".toCharArray();

    private final String clientId;
    private final ChatClient client;

    /** Text of every message this client surfaced, in the order it surfaced it. */
    private final List<String> receivedText = new CopyOnWriteArrayList<>();

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;

    HeadlessClient(File home, String displayName) throws Exception {
        JceKeyStoreManager keyStore = new JceKeyStoreManager(home);
        KeyPair identity = keyStore.loadOrCreateIdentity(PASSPHRASE.clone());
        this.clientId = PeerId.of(identity.getPublic());

        String dbPath = new File(home, "chat.db").getAbsolutePath();
        DatabaseHelper.initializeDatabase(dbPath);
        SecretKey dbKey = new SecretKeySpec(new byte[32], "AES");
        MessageRepository repository = new MessageRepository(dbPath, dbKey);

        Function<String, PublicKey> peerKeyLookup = senderId -> {
            try {
                Optional<PublicKey> stored = keyStore.getPeerKey(senderId);
                return stored.orElse(null);
            } catch (Exception e) {
                return null;
            }
        };

        this.client = new ChatClient(clientId, identity,
                new SessionManager(clientId, peerKeyLookup),
                repository, keyStore, new PeerDirectory(home), displayName);

        // Registered before connecting: ChatClient buffers anything that arrives with no listener
        // attached, and a buffered message would not be counted here.
        client.addMessageListener(new MessageListener() {
            @Override
            public void onMessageReceived(Message message) {
                if (message.getType() == MessageType.TEXT_MESSAGE) {
                    receivedText.add(new String(message.getPayload(), StandardCharsets.UTF_8));
                }
            }

            @Override
            public void onConnectionStateChanged(ConnectionState newState) {
                state = newState;
            }
        });
    }

    String id() {
        return clientId;
    }

    List<String> receivedText() {
        return receivedText;
    }

    void connect(String host, int port) {
        client.connect(host, port);
    }

    void disconnect() {
        client.disconnect();
    }

    void startSecureChat(String peerId) {
        client.startSecureChat(peerId);
    }

    void talkTo(String peerId) {
        client.setCurrentPeerId(peerId);
    }

    /** Returns the message id, or null when the session was not established and nothing went out. */
    String send(String text) {
        return client.sendMessage(text, null);
    }

    /** Blocks until the relay connection is up. */
    void awaitConnected(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (state != ConnectionState.CONNECTED && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (state != ConnectionState.CONNECTED) {
            throw new IllegalStateException(clientId + " never connected; last state was " + state);
        }
    }

    /** Blocks until the session with {@code peerId} is established. */
    void awaitSession(String peerId, long timeoutMillis) throws InterruptedException {
        talkTo(peerId);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (established()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException(failureDetail(peerId));
    }

    private boolean established() {
        Session session = client.getSession();
        return session != null && session.getState() == Session.State.ESTABLISHED;
    }

    private String failureDetail(String peerId) {
        Session session = client.getSession();
        return "no session with " + peerId + "; state was "
                + (session == null ? "none" : session.getState());
    }

    /** Blocks until this client has surfaced at least {@code count} messages. */
    void awaitReceived(int count, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (receivedText.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
