package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.ConnectionManager;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.network.SessionStateListener;
import com.e2eechat.core.session.SecureChat;
import com.e2eechat.core.session.Session;
import com.e2eechat.core.session.SessionManager;
import com.e2eechat.core.session.SessionRenewalRequiredException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The desktop client's application layer: conversations, persistence and listeners.
 *
 * <p>None of the protocol lives here. The handshake, encryption and signing come from
 * {@link SecureChat} in {@code core-shared}, which the Android client also uses. This class
 * previously carried its own copy of that orchestration; the two agreed when tested against each
 * other, but nothing kept them agreeing, and a drift between them would surface as a session that
 * establishes while each side derives a different key.
 */
public class ChatClient implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);

    /** Unit separator, between reply fields. */
    private static final char FIELD_SEP = '\u001F';
    /** Record separator, between the reply header and the message text. */
    private static final char BODY_SEP = '\u001E';
    /** Prefix identifying a body that quotes an earlier message. */
    private static final String REPLY_MARKER = "\u0001tgreply" + FIELD_SEP;

    private final String clientId;
    private final SessionManager sessionManager;
    private final MessageRepository messageRepository;
    private final IdentityKeyStore keyStoreManager;
    private final PeerDirectory peerDirectory;
    private final SecureChat secureChat;

    private ConnectionManager connectionManager;
    private String relayHost;
    private int relayPort;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Message> earlyMessageBuffer = new ArrayList<>();

    private volatile String localDisplayName;
    private String currentPeerId;

    public ChatClient(String clientId, KeyPair identityKey, SessionManager sessionManager,
                      MessageRepository messageRepository, IdentityKeyStore keyStoreManager,
                      PeerDirectory peerDirectory, String localDisplayName) {
        this.clientId = clientId;
        this.sessionManager = sessionManager;
        this.messageRepository = messageRepository;
        this.keyStoreManager = keyStoreManager;
        this.peerDirectory = peerDirectory;
        this.localDisplayName = localDisplayName;

        this.secureChat = new SecureChat(
                clientId, identityKey, sessionManager, keyStoreManager,
                this::transmit,
                peerDirectory::setName,
                localDisplayName);
    }

    /** Hands a signed frame to the relay, if we are connected. */
    private void transmit(Message message) {
        ConnectionManager connection = connectionManager;
        if (connection != null) {
            connection.sendMessage(message);
        }
    }

    // ----------------------------------------------------------------- wiring

    public void connect(String host, int port) {
        if (connectionManager != null) {
            return;
        }
        this.relayHost = host;
        this.relayPort = port;
        connectionManager = new ConnectionManager(host, port, clientId, this);
        connectionManager.start();
    }

    /** Where this client is pointed, for the settings sheet. */
    public String getRelayDescription() {
        return relayHost == null ? "Not connected" : relayHost + ":" + relayPort;
    }

    public void disconnect() {
        if (connectionManager != null) {
            connectionManager.stop();
        }
    }

    public void addMessageListener(MessageListener listener) {
        listeners.add(listener);
        synchronized (earlyMessageBuffer) {
            for (Message msg : earlyMessageBuffer) {
                listener.onMessageReceived(msg);
            }
            earlyMessageBuffer.clear();
        }
    }

    public void removeMessageListener(MessageListener listener) {
        listeners.remove(listener);
    }

    public void setCurrentPeerId(String peerId) {
        this.currentPeerId = peerId;
    }

    public Session getSession() {
        return currentPeerId == null ? null : sessionManager.getSession(currentPeerId);
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public PeerDirectory getPeerDirectory() {
        return peerDirectory;
    }

    public String getClientId() {
        return clientId;
    }

    public String getReceiverId() {
        return currentPeerId;
    }

    public String getLocalDisplayName() {
        return localDisplayName;
    }

    public void setLocalDisplayName(String name) {
        this.localDisplayName = name;
        secureChat.setLocalDisplayName(name);
    }

    /** The label to show for a peer: their chosen name, else a short form of their id. */
    public String displayNameFor(String peerId) {
        if (peerId == null) {
            return "";
        }
        if (peerId.equals(clientId)) {
            return localDisplayName == null || localDisplayName.isEmpty() ? "You" : localDisplayName;
        }
        return peerDirectory.nameFor(peerId);
    }

    public String getPeerFingerprint(String peerId) {
        try {
            Optional<PublicKey> key = keyStoreManager.getPeerKey(peerId);
            if (key.isPresent()) {
                return keyStoreManager.fingerprint(key.get());
            }
        } catch (Exception e) {
            logger.error("Failed to get fingerprint for peer {}", peerId, e);
        }
        return null;
    }

    // -------------------------------------------------------------- handshake

    public void startSecureChat(String peerId) {
        this.currentPeerId = peerId;

        if (secureChat.isEstablished(peerId)) {
            notifySessionStateChanged(Session.State.ESTABLISHED);
            return;
        }
        // Opening a chat before the relay connection is up is normal: the window is shown while
        // connect() is still running on its own thread. Leave the session idle and let the
        // reconnect path drive the handshake.
        if (connectionManager == null) {
            notifySessionStateChanged(secureChat.stateOf(peerId));
            return;
        }
        try {
            secureChat.startHandshake(peerId);
            notifySessionStateChanged(secureChat.stateOf(peerId));
        } catch (Exception e) {
            logger.error("Failed to start secure chat", e);
        }
    }

    /** Tears down the session for a peer and runs a fresh handshake. */
    public void restartSecureChat(String peerId) {
        secureChat.resetSession(peerId);
        startSecureChat(peerId);
    }

    // ---------------------------------------------------------------- inbound

    @Override
    public void onMessageReceived(Message msg) {
        if (listeners.isEmpty()) {
            synchronized (earlyMessageBuffer) {
                earlyMessageBuffer.add(msg);
            }
            return;
        }

        if (msg.getType() == MessageType.ERROR) {
            broadcast(msg);
            return;
        }

        SecureChat.Result result = secureChat.onMessage(msg);

        switch (result.outcome) {
            case DELIVERED:
                onDelivered(msg, result);
                break;

            case SESSION_ESTABLISHED:
                if (msg.getSenderId().equals(currentPeerId)) {
                    notifySessionStateChanged(Session.State.ESTABLISHED);
                }
                break;

            case PEER_KEY_CHANGED:
                logger.error("SECURITY ALERT: identity key for {} has changed",
                        PeerId.shortForm(msg.getSenderId()));
                broadcast(alert("SECURITY ALERT: The identity key for "
                        + displayNameFor(msg.getSenderId())
                        + " has changed. Possible MITM attack.", msg.getSenderId()));
                break;

            case DROPPED:
                logger.warn("Dropped {} from {}: {}", msg.getType(),
                        PeerId.shortForm(msg.getSenderId()), result.detail);
                if ("AUTHENTICATION_FAILED".equals(result.detail)) {
                    broadcast(alert("A message failed authentication and was discarded.",
                            msg.getSenderId()));
                }
                break;

            default:
                // PEER_INTRODUCED and HANDSHAKE_ADVANCED need nothing from the UI.
                break;
        }
    }

    private void onDelivered(Message msg, SecureChat.Result result) {
        if (msg.getType() != MessageType.TEXT_MESSAGE) {
            if (msg.getType() == MessageType.READ_RECEIPT) {
                messageRepository.markOutgoingRead(clientId, msg.getSenderId());
            }
            broadcast(msg);
            return;
        }

        Body body = Body.parse(new String(result.plaintext, StandardCharsets.UTF_8));

        // The unique index on message_id makes this idempotent, so a redelivery cannot duplicate.
        messageRepository.saveMessage(
                msg.getMessageId(), msg.getSenderId(), msg.getReceiverId(),
                body.text, msg.getTimestamp(), ChatMessage.Status.DELIVERED,
                body.replyToId, body.replyToSender, body.replyToPreview, false);

        sendDeliveryAck(msg.getSenderId(), msg.getMessageId());

        broadcast(new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId(msg.getSenderId())
                .setReceiverId(msg.getReceiverId())
                .setPayload(body.text.getBytes(StandardCharsets.UTF_8))
                .setIv(msg.getIv())
                .setMessageId(msg.getMessageId())
                .setTimestamp(msg.getTimestamp())
                .setSignature(msg.getSignature())
                .buildUnsigned());
    }

    private Message alert(String text, String peerId) {
        return new MessageBuilder()
                .setType(MessageType.ERROR)
                .setSenderId(peerId)
                .setReceiverId(clientId)
                .setPayload(text.getBytes(StandardCharsets.UTF_8))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
    }

    private void broadcast(Message msg) {
        for (MessageListener listener : listeners) {
            listener.onMessageReceived(msg);
        }
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state) {
        for (MessageListener listener : listeners) {
            listener.onConnectionStateChanged(state);
        }
    }

    private void notifySessionStateChanged(Session.State state) {
        for (MessageListener listener : listeners) {
            if (listener instanceof SessionStateListener) {
                ((SessionStateListener) listener).onSessionStateChanged(state);
            }
        }
    }

    // --------------------------------------------------------------- outbound

    public void sendMessage(String text) {
        sendMessage(text, null);
    }

    /**
     * Encrypts, signs and dispatches a text message, optionally quoting {@code replyTo}.
     *
     * @return the generated message id, used to correlate the delivery acknowledgement back to the
     *         bubble, or {@code null} if the session was not established and nothing was sent
     */
    public String sendMessage(String text, ChatMessage replyTo) {
        if (currentPeerId == null) {
            return null;
        }
        if (!secureChat.isEstablished(currentPeerId)) {
            // No plaintext fallback: refusing to send is the only safe outcome.
            logger.warn("Cannot send message, session not established");
            return null;
        }

        String peerId = currentPeerId;
        try {
            long timestamp = System.currentTimeMillis();
            String messageId = UUID.randomUUID().toString();

            String replyId = replyTo == null ? null : replyTo.getMessageId();
            String replySender = replyTo == null ? null : replyTo.getSender();
            String replyPreview = replyTo == null ? null : replyTo.getContent();

            byte[] body = Body.encode(text, replyId, replySender, replyPreview)
                    .getBytes(StandardCharsets.UTF_8);

            // Encrypted before it is stored. The row used to be written first, so a send that then
            // failed left history claiming a message had been sent when nothing ever left the
            // machine - and the window, seeing no id come back, drew no bubble to contradict it.
            Message encrypted = secureChat.encrypt(peerId, messageId, body);

            messageRepository.saveMessage(messageId, clientId, peerId, text, timestamp,
                    ChatMessage.Status.SENT, replyId, replySender, replyPreview, true);
            transmit(encrypted);
            return messageId;
        } catch (SessionRenewalRequiredException e) {
            // Not a failure. The key reached its send budget and a fresh handshake is already on
            // its way out; the session state carries that to the window, which shows the chat
            // re-establishing and re-enables itself when the new key lands.
            logger.info("Renewing the key for {}", PeerId.shortForm(peerId));
            notifySessionStateChanged(secureChat.stateOf(peerId));
            return null;
        } catch (Exception e) {
            logger.error("Error creating/sending encrypted message", e);
            return null;
        }
    }

    /** Sends a signed typing notification. Best-effort: a failure here is never surfaced. */
    public void sendTyping(String peerId, boolean typing) {
        sendSignal(peerId, MessageType.TYPING, new byte[]{(byte) (typing ? 1 : 0)},
                "typing notification");
    }

    /** Tells a peer their messages have been read, promoting their ticks to READ. */
    public void sendReadReceipt(String peerId) {
        sendSignal(peerId, MessageType.READ_RECEIPT, new byte[0], "read receipt");
    }

    private void sendDeliveryAck(String peerId, String messageId) {
        if (messageId == null) {
            return;
        }
        sendSignal(peerId, MessageType.DELIVERY_ACK,
                messageId.getBytes(StandardCharsets.UTF_8), "delivery ack");
    }

    /**
     * Signs and sends a control frame. These carry no message content, so they are signed but not
     * encrypted; the relay already learns the sender/receiver pair from the routing header.
     */
    private void sendSignal(String peerId, MessageType type, byte[] payload, String description) {
        if (peerId == null || connectionManager == null || !secureChat.isEstablished(peerId)) {
            return;
        }
        try {
            transmit(secureChat.signal(peerId, type, payload));
        } catch (Exception e) {
            logger.debug("Failed to send {}", description, e);
        }
    }

    // ------------------------------------------------------------------- body

    /**
     * A decoded message body: the visible text, plus the quoted message if there was one.
     *
     * <p>Reply metadata travels inside the ciphertext rather than in header fields, so the relay
     * cannot see who is quoting whom. Bodies with no quote are sent verbatim, which keeps the format
     * readable by peers that predate replies.
     */
    private static final class Body {
        final String text;
        final String replyToId;
        final String replyToSender;
        final String replyToPreview;

        private Body(String text, String replyToId, String replyToSender, String replyToPreview) {
            this.text = text;
            this.replyToId = replyToId;
            this.replyToSender = replyToSender;
            this.replyToPreview = replyToPreview;
        }

        static Body parse(String raw) {
            if (!raw.startsWith(REPLY_MARKER)) {
                return new Body(raw, null, null, null);
            }
            int end = raw.indexOf(BODY_SEP);
            if (end < 0) {
                // Malformed header: show the text rather than dropping the message.
                return new Body(raw, null, null, null);
            }
            String[] parts = raw.substring(REPLY_MARKER.length(), end)
                    .split(String.valueOf(FIELD_SEP), -1);
            String text = raw.substring(end + 1);
            if (parts.length < 3) {
                return new Body(text, null, null, null);
            }
            return new Body(text,
                    parts[0].isEmpty() ? null : parts[0],
                    parts[1].isEmpty() ? null : parts[1],
                    parts[2].isEmpty() ? null : parts[2]);
        }

        static String encode(String text, String replyId, String replySender, String replyPreview) {
            if (replyId == null) {
                return text;
            }
            return REPLY_MARKER + replyId + FIELD_SEP
                    + (replySender == null ? "" : replySender) + FIELD_SEP
                    + (replyPreview == null ? "" : replyPreview.replace('\n', ' ')) + BODY_SEP
                    + text;
        }
    }
}
