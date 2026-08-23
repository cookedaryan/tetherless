package com.e2eechat.desktop;
import com.e2eechat.core.network.SessionStateListener;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.ConnectionManager;

import com.e2eechat.core.crypto.AESUtils;
import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.crypto.DHUtils;
import com.e2eechat.core.keys.IdentityKeyStore;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.MessageSigner;
import com.e2eechat.core.session.Session;
import com.e2eechat.core.session.SessionManager;
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
import javax.crypto.spec.SecretKeySpec;

public class ChatClient implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);

    private final String clientId;
    private final KeyPair identityKey;
    private final SessionManager sessionManager;
    private final MessageRepository messageRepository;
    private final IdentityKeyStore keyStoreManager;

    private ConnectionManager connectionManager;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Message> earlyMessageBuffer = new ArrayList<>();

    /** Names peers have asked to be shown as; no longer derivable from their ids. */
    private final PeerDirectory peerDirectory;

    /** The name this client asks to be shown as. Metadata only - it is not part of our id. */
    private volatile String localDisplayName;

    private String currentPeerId = null;

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

    public PeerDirectory getPeerDirectory() {
        return peerDirectory;
    }

    public String getLocalDisplayName() {
        return localDisplayName;
    }

    /**
     * Changes the name this client presents. Because the name is metadata rather than part of the
     * id, this no longer changes the address peers route to - established sessions keep working.
     */
    public void setLocalDisplayName(String name) {
        this.localDisplayName = name;
    }

    /** Our identity key plus the name we ask to be shown as, as a HELLO body. */
    private byte[] helloPayload() throws Exception {
        return HelloPayload.encode(identityKey.getPublic(), localDisplayName);
    }

    public void connect(String host, int port) {
        if (connectionManager != null) {
            return;
        }
        connectionManager = new ConnectionManager(host, port, clientId, this);
        connectionManager.start();
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
        if (currentPeerId == null) {
            return null;
        }
        return sessionManager.getSession(currentPeerId);
    }

    public MessageRepository getMessageRepository() {
        return messageRepository;
    }

    public void startSecureChat(String peerId) {
        this.currentPeerId = peerId;
        Session session = sessionManager.getSession(peerId);
        if (session.getState() == Session.State.ESTABLISHED) {
            notifySessionStateChanged(session.getState());
            return;
        }

        // Opening a chat before the relay connection is up is normal - the window is shown while
        // connect() is still running on its own thread. Leave the session IDLE and let the
        // reconnect path retry rather than dereferencing a null connection.
        if (connectionManager == null) {
            notifySessionStateChanged(session.getState());
            return;
        }

        try {
            // 1. Send HELLO with our Identity Public Key to initiate Trust On First Use
            Message helloMsg = new MessageBuilder()
                    .setType(MessageType.HELLO)
                    .setSenderId(clientId)
                    .setReceiverId(peerId)
                    .setPayload(helloPayload())
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .buildUnsigned();
            connectionManager.sendMessage(helloMsg);

            // 2. Generate DH Keypair and send KEY_EXCHANGE_INIT
            KeyPair dhPair = DHUtils.generateKeyPair();
            session.setLocalDhPublicKey(dhPair.getPublic());
            dhPrivateKeys.put(peerId, dhPair.getPrivate());

            Message initMsg = new MessageBuilder()
                    .setType(MessageType.KEY_EXCHANGE_INIT)
                    .setSenderId(clientId)
                    .setReceiverId(peerId)
                    .setPayload(dhPair.getPublic().getEncoded())
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .buildUnsigned();

            Message signed = MessageSigner.sign(initMsg, identityKey.getPrivate());

            session.setState(Session.State.HANDSHAKE_SENT);
            connectionManager.sendMessage(signed);
            notifySessionStateChanged(session.getState());

        } catch (Exception e) {
            logger.error("Failed to start secure chat", e);
        }
    }

    private final java.util.Map<String, java.security.PrivateKey> dhPrivateKeys = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public void onMessageReceived(Message msg) {
        if (listeners.isEmpty()) {
            synchronized (earlyMessageBuffer) { earlyMessageBuffer.add(msg); }
            return;
        }

        if (msg.getType() == MessageType.ERROR) {
            for (MessageListener listener : listeners) {
                listener.onMessageReceived(msg);
            }
            return;
        }

        SessionManager.ProcessResult result = sessionManager.onMessage(msg);
        Session session = sessionManager.getSession(msg.getSenderId());

        try {
            if (msg.getType() == MessageType.HELLO) {
                HelloPayload hello = HelloPayload.decode(msg.getPayload());
                PublicKey receivedKey = hello.getPublicKey();

                // The sender's id must actually be the hash of the key they just presented.
                // Without this a peer could claim someone else's address and, on a first contact,
                // have their own key stored against it.
                String derivedId = PeerId.of(receivedKey);
                if (!derivedId.equals(msg.getSenderId())) {
                    logger.warn("Dropping HELLO: sender id {} does not match its key ({})",
                            msg.getSenderId(), derivedId);
                    return;
                }

                // Self-asserted metadata, recorded for display only.
                peerDirectory.setName(msg.getSenderId(), hello.getDisplayName());

                Optional<PublicKey> existingKey = keyStoreManager.getPeerKey(msg.getSenderId());
                if (!existingKey.isPresent()) {
                    logger.info("TOFU: Storing new key for peer {}", msg.getSenderId());
                    keyStoreManager.storePeerKey(msg.getSenderId(), receivedKey);

                    // Reply with our HELLO if we haven't already
                    Message helloReply = new MessageBuilder()
                            .setType(MessageType.HELLO)
                            .setSenderId(clientId)
                            .setReceiverId(msg.getSenderId())
                            .setPayload(helloPayload())
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    connectionManager.sendMessage(helloReply);
                } else if (!existingKey.get().equals(receivedKey)) {
                    logger.error("SECURITY ALERT: Key for peer {} has changed!", msg.getSenderId());
                    // Notifying UI via a special error message
                    Message alert = new MessageBuilder()
                            .setType(MessageType.ERROR)
                            .setSenderId(msg.getSenderId())
                            .setReceiverId(clientId)
                            .setPayload(("SECURITY ALERT: The identity key for " + msg.getSenderId() + " has changed! Possible MITM attack.").getBytes(StandardCharsets.UTF_8))
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    for (MessageListener listener : listeners) {
                        listener.onMessageReceived(alert);
                    }
                }
                return;
            }

            switch (result.outcome) {
                case HANDSHAKE_PROCEED:
                    if (msg.getType() == MessageType.KEY_EXCHANGE_INIT) {
                        PublicKey remoteDh = DHUtils.getPublicKeyFromBytes(msg.getPayload());
                        session.setRemoteDhPublicKey(remoteDh);

                        KeyPair dhPair = DHUtils.generateKeyPair();
                        session.setLocalDhPublicKey(dhPair.getPublic());

                        byte[] salt = new byte[32];
                        byte[] info = "tetherless-v1 aes-256-gcm".getBytes();
                        byte[] sharedSecret = DHUtils.generateSharedSecret(dhPair.getPrivate(), remoteDh, salt, info);
                        session.setSecretKey(new SecretKeySpec(sharedSecret, "AES"));
                        session.setState(Session.State.ESTABLISHED);

                        Message replyMsg = new MessageBuilder()
                                .setType(MessageType.KEY_EXCHANGE_REPLY)
                                .setSenderId(clientId)
                                .setReceiverId(msg.getSenderId())
                                .setPayload(dhPair.getPublic().getEncoded())
                                .setMessageId(UUID.randomUUID().toString())
                                .setTimestamp(System.currentTimeMillis())
                                .buildUnsigned();

                        Message signedReply = MessageSigner.sign(replyMsg, identityKey.getPrivate());
                        connectionManager.sendMessage(signedReply);

                        if (msg.getSenderId().equals(currentPeerId)) {
                            notifySessionStateChanged(session.getState());
                        }
                    } else if (msg.getType() == MessageType.KEY_EXCHANGE_REPLY) {
                        PublicKey remoteDh = DHUtils.getPublicKeyFromBytes(msg.getPayload());
                        session.setRemoteDhPublicKey(remoteDh);

                        java.security.PrivateKey myDhPriv = dhPrivateKeys.remove(msg.getSenderId());
                        if (myDhPriv != null) {
                            byte[] salt = new byte[32];
                            byte[] info = "tetherless-v1 aes-256-gcm".getBytes();
                            byte[] sharedSecret = DHUtils.generateSharedSecret(myDhPriv, remoteDh, salt, info);
                            session.setSecretKey(new SecretKeySpec(sharedSecret, "AES"));
                            session.setState(Session.State.ESTABLISHED);

                            if (msg.getSenderId().equals(currentPeerId)) {
                                notifySessionStateChanged(session.getState());
                            }
                        }
                    }
                    break;
                case DELIVER:
                    if (msg.getType() == MessageType.TEXT_MESSAGE) {
                        Body body = Body.parse(new String(result.plaintext, StandardCharsets.UTF_8));

                        // The UNIQUE index on message_id makes this insert idempotent, so a
                        // redelivered message cannot produce a duplicate row.
                        messageRepository.saveMessage(
                                msg.getMessageId(), msg.getSenderId(), msg.getReceiverId(),
                                body.text, msg.getTimestamp(), ChatMessage.Status.DELIVERED,
                                body.replyToId, body.replyToSender, body.replyToPreview, false);

                        // Acknowledge so the sender's ticks can advance to delivered.
                        sendDeliveryAck(msg.getSenderId(), msg.getMessageId());

                        Message decryptedMsg = new MessageBuilder()
                                .setType(MessageType.TEXT_MESSAGE)
                                .setSenderId(msg.getSenderId())
                                .setReceiverId(msg.getReceiverId())
                                .setPayload(body.text.getBytes(StandardCharsets.UTF_8))
                                .setIv(msg.getIv())
                                .setMessageId(msg.getMessageId())
                                .setTimestamp(msg.getTimestamp())
                                .setSignature(msg.getSignature())
                                .buildUnsigned();

                        for (MessageListener listener : listeners) {
                            listener.onMessageReceived(decryptedMsg);
                        }
                    } else if (msg.getType() == MessageType.TYPING
                            || msg.getType() == MessageType.DELIVERY_ACK) {
                        // Signalling frames carry no ciphertext; hand them straight to the UI.
                        for (MessageListener listener : listeners) {
                            listener.onMessageReceived(msg);
                        }
                    } else if (msg.getType() == MessageType.READ_RECEIPT) {
                        messageRepository.markOutgoingRead(clientId, msg.getSenderId());
                        for (MessageListener listener : listeners) {
                            listener.onMessageReceived(msg);
                        }
                    }
                    break;
                case DROP_REPLAY:
                case DROP_BAD_SIGNATURE:
                case DROP_NO_SESSION:
                case HANDSHAKE_DROPPED:
                    logger.warn("Dropped message from {} reason {}", msg.getSenderId(), result.outcome);
                    break;
            }
        } catch (Exception e) {
            logger.error("Failed to process message", e);
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

    /** Unit separator, between reply fields. */
    private static final char FIELD_SEP = '\u001F';
    /** Record separator, between the reply header and the message text. */
    private static final char BODY_SEP = '\u001E';
    /** Prefix identifying a body that quotes an earlier message. */
    private static final String REPLY_MARKER = "\u0001tgreply" + FIELD_SEP;

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
            String[] parts = raw.substring(REPLY_MARKER.length(), end).split(String.valueOf(FIELD_SEP), -1);
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
        Session session = sessionManager.getSession(currentPeerId);

        if (session.getState() != Session.State.ESTABLISHED) {
            // No plaintext fallback: refusing to send is the only safe outcome here.
            logger.warn("Cannot send message, session not established");
            return null;
        }

        try {
            long timestamp = System.currentTimeMillis();
            String messageId = UUID.randomUUID().toString();

            String replyId = replyTo == null ? null : replyTo.getMessageId();
            String replySender = replyTo == null ? null : replyTo.getSender();
            String replyPreview = replyTo == null ? null : replyTo.getContent();

            messageRepository.saveMessage(messageId, clientId, currentPeerId, text, timestamp,
                    ChatMessage.Status.SENT, replyId, replySender, replyPreview, true);

            byte[] iv = sessionManager.generateIv(session, true);
            byte[] plaintext = Body.encode(text, replyId, replySender, replyPreview)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = AESUtils.encrypt(plaintext, session.getSecretKey(), iv);

            Message msg = new MessageBuilder()
                    .setType(MessageType.TEXT_MESSAGE)
                    .setSenderId(clientId)
                    .setReceiverId(currentPeerId)
                    .setPayload(ciphertext)
                    .setIv(iv)
                    .setMessageId(messageId)
                    .setTimestamp(timestamp)
                    .buildUnsigned();

            Message signed = MessageSigner.sign(msg, identityKey.getPrivate());

            if (connectionManager != null) {
                connectionManager.sendMessage(signed);
            }
            return messageId;
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
        if (peerId == null || connectionManager == null) {
            return;
        }
        if (sessionManager.getSession(peerId).getState() != Session.State.ESTABLISHED) {
            return;
        }
        try {
            Message msg = new MessageBuilder()
                    .setType(type)
                    .setSenderId(clientId)
                    .setReceiverId(peerId)
                    .setPayload(payload)
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .buildUnsigned();
            connectionManager.sendMessage(MessageSigner.sign(msg, identityKey.getPrivate()));
        } catch (Exception e) {
            logger.debug("Failed to send {}", description, e);
        }
    }

    /** Tears down the session for a peer and runs a fresh handshake. */
    public void restartSecureChat(String peerId) {
        sessionManager.getSession(peerId).setState(Session.State.IDLE);
        dhPrivateKeys.remove(peerId);
        startSecureChat(peerId);
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

    public String getClientId() { return clientId; }
    public String getReceiverId() { return currentPeerId; }
}
