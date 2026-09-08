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

    /** How long one handshake attempt is given before another is made. */
    private static final long HANDSHAKE_RETRY_MILLIS = 2000;

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

    /**
     * Runs the handshake with {@code peerId}, retrying until a session stands up.
     *
     * <p>The retry is not padding around a flaky test. The relay acknowledges nothing after a
     * client's opening HELLO, so a client has no way to know when the relay has finished
     * registering it and it has become routable. A handshake aimed at a peer who connected a
     * moment ago can therefore arrive first and be answered with RECIPIENT_OFFLINE, and since
     * HELLO and KEY_EXCHANGE_INIT travel together, the attempt fails as a unit and leaves nothing
     * in flight. Retrying is exactly what a person does when a chat does not open, and each
     * attempt is self-contained.
     */
    void establishSessionWith(String peerId, long timeoutMillis) throws InterruptedException {
        talkTo(peerId);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            client.startSecureChat(peerId);

            // Long enough that a reply to this attempt cannot still be in flight when the next
            // one generates fresh key material.
            long attemptDeadline =
                    Math.min(deadline, System.currentTimeMillis() + HANDSHAKE_RETRY_MILLIS);
            while (System.currentTimeMillis() < attemptDeadline) {
                if (established()) {
                    return;
                }
                Thread.sleep(20);
            }
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
