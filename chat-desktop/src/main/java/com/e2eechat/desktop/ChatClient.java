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
    private final KeyPair identityKey;
    private final SessionManager sessionManager;
    private final MessageRepository messageRepository;
    private final IdentityKeyStore keyStoreManager;
    private final PeerDirectory peerDirectory;
    private final SecureChat secureChat;

    private ConnectionManager connectionManager;
    private String relayHost;
    private int relayPort;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private final List<OutboxListener> outboxListeners = new CopyOnWriteArrayList<>();
    private final List<Message> earlyMessageBuffer = new ArrayList<>();

    private volatile String localDisplayName;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private String currentPeerId;

    public ChatClient(String clientId, KeyPair identityKey, SessionManager sessionManager,
                      MessageRepository messageRepository, IdentityKeyStore keyStoreManager,
                      PeerDirectory peerDirectory, String localDisplayName) {
        this.clientId = clientId;
        this.identityKey = identityKey;
        this.sessionManager = sessionManager;
        this.messageRepository = messageRepository;
        this.keyStoreManager = keyStoreManager;
        this.peerDirectory = peerDirectory;
        this.localDisplayName = localDisplayName;

        this.secureChat = new SecureChat(
                clientId, identityKey, sessionManager, keyStoreManager,
                message -> transmit(message),
                peerDirectory::setName,
                localDisplayName);
    }

    /** Hands a signed frame to the relay. False when there was no connection to take it. */
    private boolean transmit(Message message) {
        ConnectionManager connection = connectionManager;
        return connection != null && connection.sendMessage(message);
    }

    // ----------------------------------------------------------------- wiring

    public void connect(String host, int port) {
        if (connectionManager != null) {
            return;
        }
        this.relayHost = host;
        this.relayPort = port;
        connectionManager = new ConnectionManager(host, port, clientId, identityKey, this);
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

    public void addOutboxListener(OutboxListener listener) {
        outboxListeners.add(listener);
    }

    public void removeOutboxListener(OutboxListener listener) {
        outboxListeners.remove(listener);
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
                // Whether or not this is the conversation on screen: the queue belongs to the
                // peer, not to whichever chat happens to be open.
                flushPending(msg.getSenderId());
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
            } else if (msg.getType() == MessageType.DELIVERY_ACK) {
                // The codec allows a null payload, and this runs on the reader thread: reading it
                // without checking threw a NullPointerException that unwound into the read loop,
                // which caught it, gave up and closed the socket. One 20-byte frame was enough to
                // put a client offline. An acknowledgement with no message id says nothing, so it
                // is dropped rather than broadcast.
                byte[] ackedId = msg.getPayload();
                if (ackedId == null || ackedId.length == 0) {
                    logger.warn("Ignoring a DELIVERY_ACK from {} with no message id",
                            PeerId.shortForm(msg.getSenderId()));
                    return;
                }
                // Persisted here rather than in the window. A tick that lives only in the
                // transcript is gone on restart, and the window only updated it when a
                // conversation happened to be open, so an acknowledgement arriving at any other
                // moment was dropped.
                messageRepository.updateStatus(
                        new String(ackedId, StandardCharsets.UTF_8),
                        ChatMessage.Status.DELIVERED);
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
        connectionState = state;
        if (state == ConnectionState.CONNECTED) {
            // Sessions that survived the outage can drain immediately; the rest drain from
            // SESSION_ESTABLISHED as each handshake completes.
            flushAllPending();
        }
        for (MessageListener listener : listeners) {
            listener.onConnectionStateChanged(state);
        }
    }

    /**
     * The last state the transport reported. Panels that describe the connection read this when
     * they open rather than subscribing, so a sheet that is closed leaves no listener behind.
     */
    public ConnectionState getConnectionState() {
        return connectionState;
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
     * <p>Returns the message as it was recorded, so the window draws the row that exists rather
     * than reconstructing one beside it - the bubble's timestamp and delivery state are then the
     * stored ones, not a second guess made a moment later.
     *
     * @return the recorded message, or {@code null} only when there is no conversation to send to
     */
    public ChatMessage sendMessage(String text, ChatMessage replyTo) {
        if (currentPeerId == null) {
            return null;
        }

        String peerId = currentPeerId;
        long timestamp = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        String replyId = replyTo == null ? null : replyTo.getMessageId();
        String replySender = replyTo == null ? null : replyTo.getSender();
        String replyPreview = replyTo == null ? null : replyTo.getContent();

        // No session yet - the peer has never been reached, or is not reachable now. The message is
        // written down rather than dropped, and the handshake is started so the outbox has
        // something to drain into. Never a plaintext fallback; queuing is the safe outcome.
        if (!secureChat.isEstablished(peerId)) {
            logger.info("Queuing message for {}: no session yet", PeerId.shortForm(peerId));
            startSecureChat(peerId);
            return record(messageId, peerId, text, timestamp, ChatMessage.Status.PENDING,
                    replyId, replySender, replyPreview);
        }

        try {
            byte[] body = Body.encode(text, replyId, replySender, replyPreview)
                    .getBytes(StandardCharsets.UTF_8);

            // Encrypted before it is stored. The row used to be written first, so a send that then
            // failed left history claiming a message had been sent when nothing ever left the
            // machine - and the window, seeing no id come back, drew no bubble to contradict it.
            Message encrypted = secureChat.encrypt(peerId, messageId, body);

            boolean accepted = transmit(encrypted);
            return record(messageId, peerId, text, timestamp,
                    accepted ? ChatMessage.Status.SENT : ChatMessage.Status.PENDING,
                    replyId, replySender, replyPreview);
        } catch (SessionRenewalRequiredException e) {
            // Not a failure. The key reached its send budget and a fresh handshake is already on
            // its way out; the session state carries that to the window, which shows the chat
            // re-establishing and re-enables itself when the new key lands.
            logger.info("Renewing the key for {}", PeerId.shortForm(peerId));
            // Queued, not discarded: the renewal is already in flight and the outbox drains as
            // soon as the new key lands, so what was typed survives the rekey.
            ChatMessage queued = record(messageId, peerId, text, timestamp,
                    ChatMessage.Status.PENDING, replyId, replySender, replyPreview);
            // The handshake goes out from inside encrypt(), so the reply can land - and the
            // flush-on-establish fire - before this row exists. Draining here too means the
            // message is not left queued behind a session that is already back.
            flushPending(peerId);
            notifySessionStateChanged(secureChat.stateOf(peerId));
            return queued;
        } catch (Exception e) {
            logger.error("Error creating/sending encrypted message", e);
            return record(messageId, peerId, text, timestamp, ChatMessage.Status.FAILED,
                    replyId, replySender, replyPreview);
        }
    }

    /** Writes an outgoing row and hands back the object the window will draw. */
    private ChatMessage record(String messageId, String peerId, String text, long timestamp,
                               ChatMessage.Status status, String replyId, String replySender,
                               String replyPreview) {
        messageRepository.saveMessage(messageId, clientId, peerId, text, timestamp, status,
                replyId, replySender, replyPreview, true);
        return new ChatMessage(messageId, clientId, peerId, text, timestamp, status,
                replyId, replySender, replyPreview);
    }

    /**
     * Sends anything queued for {@code peerId}, oldest first.
     *
     * <p>Called when a session is established and after the transport reconnects. Each message is
     * encrypted now rather than when it was typed: the session it was queued under may be long
     * gone, and the stored row is the plaintext the user wrote, not a frame.
     *
     * <p>Stops at the first refusal. The rest stay queued, and order is preserved - draining past
     * a failure would deliver a conversation out of sequence.
     */
    private void flushPending(String peerId) {
        if (!secureChat.isEstablished(peerId)) {
            return;
        }
        List<ChatMessage> pending = messageRepository.getPending(clientId, peerId);
        if (pending.isEmpty()) {
            return;
        }
        logger.info("Flushing {} queued message(s) for {}",
                pending.size(), PeerId.shortForm(peerId));

        for (ChatMessage queued : pending) {
            try {
                byte[] body = Body.encode(queued.getContent(), queued.getReplyToId(),
                        queued.getReplyToSender(), queued.getReplyToPreview())
                        .getBytes(StandardCharsets.UTF_8);
                Message encrypted = secureChat.encrypt(peerId, queued.getMessageId(), body);
                if (!transmit(encrypted)) {
                    return;
                }
                messageRepository.updateStatus(queued.getMessageId(), ChatMessage.Status.SENT);
                for (OutboxListener listener : outboxListeners) {
                    listener.onQueuedMessageSent(peerId, queued.getMessageId());
                }
            } catch (SessionRenewalRequiredException e) {
                // The rekey is in flight; the rest of the queue waits for it.
                return;
            } catch (Exception e) {
                logger.error("Failed to flush a queued message", e);
                return;
            }
        }
    }

    /** Drains every peer's outbox. Used when the transport comes back. */
    private void flushAllPending() {
        for (String peerId : messageRepository.getPendingPeers(clientId)) {
            flushPending(peerId);
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
