package com.e2eechat.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.ConnectionManager;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.session.SecureChat;
import com.e2eechat.core.session.Session;
import com.e2eechat.core.session.SessionManager;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Holds the relay connection and performs all message cryptography.
 *
 * <p>The handshake and encryption are not implemented here: they come from
 * {@link SecureChat} in {@code core-shared}, the same engine the desktop client uses. Two
 * implementations of a key exchange drift apart, and the symptom is a session that establishes
 * while each side derives a different key.
 *
 * <p>Every cryptographic and database operation runs on {@link #worker}. A 2048-bit
 * Diffie-Hellman operation is slow enough on a low-end device to trigger an ANR if it ever reached
 * the main thread.
 */
public class ChatService extends Service implements MessageListener {

    private static final String TAG = "ChatService";
    private static final String CHANNEL_ID = "ChatServiceChannel";

    private final IBinder binder = new LocalBinder();

    private ConnectionManager connectionManager;
    private SecureChat secureChat;
    private AndroidKeyStoreManager keyStore;
    private String clientId;

    /** Held so a reconnect can re-prove ownership of {@link #clientId} to the relay. */
    private KeyPair identityKey;

    /** All crypto and database work. Nothing touching either belongs on the main thread. */
    private ExecutorService worker;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final MutableLiveData<String> connectionStateLive = new MutableLiveData<>("DISCONNECTED");
    private final MutableLiveData<String> sessionStateLive = new MutableLiveData<>("IDLE");
    private final MutableLiveData<String> securityAlertLive = new MutableLiveData<>();

    /** The peer the UI currently has open, so its session state can be surfaced. */
    private volatile String currentPeerId;

    public class LocalBinder extends Binder {
        public ChatService getService() {
            return ChatService.this;
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification("Starting..."));

        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ChatService-Crypto");
            t.setDaemon(true);
            return t;
        });

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "E2EEChat::NetworkWakeLock");
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "E2EEChat::NetworkWifiLock");
        }

        // Generating an RSA identity takes seconds on first run, so it cannot block onCreate.
        worker.execute(this::initialiseIdentity);
    }

    /**
     * Loads or creates the device identity, derives the peer id from it, and wires up the shared
     * crypto engine. Runs on {@link #worker}.
     */
    private void initialiseIdentity() {
        try {
            keyStore = new AndroidKeyStoreManager(this);
            KeyPair identity = keyStore.loadOrCreateIdentity(null);
            identityKey = identity;

            // The address is a pure function of the key: it survives renames and reinstalls of the
            // app's data, though not of the keystore entry itself.
            clientId = PeerId.of(identity.getPublic());
            Log.i(TAG, "identity ready, peer id " + PeerId.shortForm(clientId));

            Function<String, PublicKey> peerKeyLookup = peerId -> {
                try {
                    return keyStore.getPeerKey(peerId).orElse(null);
                } catch (Exception e) {
                    return null;
                }
            };
            SessionManager sessionManager = new SessionManager(clientId, peerKeyLookup);

            connectionManager = new ConnectionManager(
                    MobileConfig.relayHost(this), MobileConfig.relayPort(this), clientId,
                    identityKey, this);

            secureChat = new SecureChat(
                    clientId, identity, sessionManager, keyStore,
                    msg -> connectionManager.sendMessage(msg),
                    (peerId, name) -> PeerNames.put(this, peerId, name),
                    MobileConfig.displayName(this));

            registerNetworkCallback();
            connectionManager.start();
        } catch (Exception e) {
            Log.e(TAG, "could not initialise identity", e);
            connectionStateLive.postValue("IDENTITY_FAILED");
        }
    }

    public void restartConnection() {
        worker.execute(() -> {
            if (connectionManager != null) {
                connectionManager.stop();
                connectionManager = new ConnectionManager(
                        MobileConfig.relayHost(this), MobileConfig.relayPort(this), clientId,
                        identityKey, this);
                connectionManager.start();
            }
        });
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "network available; connecting");
                if (connectionManager != null) {
                    connectionManager.start();
                }
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "network lost; pausing");
                if (connectionManager != null) {
                    connectionManager.stop();
                }
            }
        };
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        if (connectionManager != null) {
            connectionManager.stop();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
        // Session keys are in-memory only; dropping the engine is what discards them. A restart
        // therefore needs a fresh handshake, which is the forward secrecy being paid for.
        secureChat = null;
        releaseLocks();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- inbound

    @Override
    public void onMessageReceived(Message message) {
        // The reader thread must not do crypto or database work.
        worker.execute(() -> handleMessage(message));
    }

    private void handleMessage(Message message) {
        SecureChat chat = secureChat;
        if (chat == null) {
            return;
        }
        SecureChat.Result result = chat.onMessage(message);

        switch (result.outcome) {
            case DELIVERED:
                if (message.getType() == MessageType.TEXT_MESSAGE) {
                    persistIncoming(message, result.plaintext);
                    acknowledge(message);
                }
                break;

            case SESSION_ESTABLISHED:
                if (result.peerId.equals(currentPeerId)) {
                    sessionStateLive.postValue(Session.State.ESTABLISHED.name());
                }
                break;

            case PEER_KEY_CHANGED:
                // Never silently accept a new key for a known peer: that is exactly the hole a
                // malicious relay walks through.
                Log.e(TAG, "identity key changed for " + PeerId.shortForm(result.peerId));
                securityAlertLive.postValue("The identity key for "
                        + PeerNames.get(this, result.peerId)
                        + " has changed. Do not trust this chat until you have compared safety "
                        + "numbers again.");
                break;

            case DROPPED:
                Log.w(TAG, "dropped " + message.getType() + " from "
                        + PeerId.shortForm(message.getSenderId()) + ": " + result.detail);
                if ("AUTHENTICATION_FAILED".equals(result.detail)) {
                    securityAlertLive.postValue(
                            "A message failed authentication and was discarded.");
                }
                break;

            default:
                break;
        }
    }

    private void persistIncoming(Message message, byte[] plaintext) {
        MessageEntity entity = new MessageEntity();
        entity.messageId = message.getMessageId();
        entity.conversationId = message.getSenderId();
        entity.sender = message.getSenderId();
        entity.receiver = clientId;
        entity.content = new String(plaintext, StandardCharsets.UTF_8);
        entity.direction = "IN";
        entity.deliveryState = "DELIVERED";
        entity.sentAt = message.getTimestamp();
        entity.receivedAt = System.currentTimeMillis();
        // The unique index on messageId makes a redelivery a no-op rather than a duplicate row.
        DatabaseProvider.getDatabase(this).messageDao().insert(entity);
    }

    private void acknowledge(Message message) {
        try {
            connectionManager.sendMessage(secureChat.signal(message.getSenderId(),
                    MessageType.DELIVERY_ACK,
                    message.getMessageId().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.d(TAG, "could not acknowledge message", e);
        }
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state) {
        connectionStateLive.postValue(state.name());
        if (state == ConnectionState.CONNECTED) {
            worker.execute(this::flushPendingMessages);
        }
    }

    // --------------------------------------------------------------- outbound

    /** Begins a secure session with a peer. Returns immediately; watch the session LiveData. */
    public void startSecureChat(String peerId) {
        currentPeerId = peerId;
        sessionStateLive.postValue("HANDSHAKE_SENT");
        worker.execute(() -> {
            try {
                SecureChat chat = secureChat;
                if (chat == null) {
                    return;
                }
                if (chat.isEstablished(peerId)) {
                    sessionStateLive.postValue(Session.State.ESTABLISHED.name());
                    return;
                }
                chat.startHandshake(peerId);
            } catch (Exception e) {
                Log.e(TAG, "handshake failed", e);
                sessionStateLive.postValue("FAILED");
            }
        });
    }

    /**
     * Encrypts and sends a message. The row is written first with {@code PENDING}, so an outgoing
     * message survives being composed while offline and is flushed on reconnect.
     */
    public void sendTextMessage(String peerId, String text) {
        String messageId = UUID.randomUUID().toString();
        worker.execute(() -> {
            MessageEntity entity = new MessageEntity();
            entity.messageId = messageId;
            entity.conversationId = peerId;
            entity.sender = clientId;
            entity.receiver = peerId;
            entity.content = text;
            entity.direction = "OUT";
            entity.deliveryState = "PENDING";
            entity.sentAt = System.currentTimeMillis();
            entity.receivedAt = entity.sentAt;
            DatabaseProvider.getDatabase(this).messageDao().insert(entity);

            dispatch(peerId, text, messageId);
        });
    }

    /** Encrypts, signs and hands one message to the transport. Must run on {@link #worker}. */
    private void dispatch(String peerId, String text, String messageId) {
        SecureChat chat = secureChat;
        if (chat == null || connectionManager == null) {
            return;
        }
        if (!chat.isEstablished(peerId)) {
            // No plaintext fallback: leave it PENDING until a session exists.
            Log.w(TAG, "no session with " + PeerId.shortForm(peerId) + "; message left pending");
            return;
        }
        boolean held = false;
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(10_000L);
                held = true;
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.acquire();
            }
            Message encrypted = chat.encrypt(peerId, messageId,
                    text.getBytes(StandardCharsets.UTF_8));
            connectionManager.sendMessage(encrypted);
            DatabaseProvider.getDatabase(this).messageDao().updateDeliveryState(messageId, "SENT");
        } catch (Exception e) {
            Log.e(TAG, "could not send message", e);
            DatabaseProvider.getDatabase(this).messageDao().updateDeliveryState(messageId, "FAILED");
        } finally {
            if (held && wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        }
    }

    private void flushPendingMessages() {
        try {
            List<MessageEntity> pending =
                    DatabaseProvider.getDatabase(this).messageDao().getPendingMessages("PENDING");
            if (pending == null) {
                return;
            }
            for (MessageEntity m : pending) {
                dispatch(m.conversationId, m.content, m.messageId);
            }
        } catch (Exception e) {
            Log.e(TAG, "could not flush pending messages", e);
        }
    }

    // ------------------------------------------------------------------ state

    public LiveData<String> getConnectionStateLiveData() {
        return connectionStateLive;
    }

    public LiveData<String> getSessionStateLiveData() {
        return sessionStateLive;
    }

    /** Security notices the user must see: key changes and authentication failures. */
    public LiveData<String> getSecurityAlertLiveData() {
        return securityAlertLive;
    }

    /** This device's peer id, or null until the identity has been generated. */
    public String getClientId() {
        return clientId;
    }

    /**
     * The safety number for a peer, or null if their key has not been received yet.
     *
     * <p>Signatures only prove a key is used consistently; they cannot prove it belongs to the
     * right person. Comparing this with the peer over a channel you already trust is what rules out
     * a relay having substituted its own key.
     */
    public String safetyNumberFor(String peerId) {
        try {
            if (keyStore == null || peerId == null) {
                return null;
            }
            PublicKey key = keyStore.getPeerKey(peerId).orElse(null);
            return key == null ? null : keyStore.fingerprint(key);
        } catch (Exception e) {
            return null;
        }
    }

    // ----------------------------------------------------------- housekeeping

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Tetherless")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Chat Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
