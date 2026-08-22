package com.e2eechat.desktop;

import com.e2eechat.core.crypto.AESUtils;
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
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.JOptionPane;

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
    
    private String currentPeerId = null;

    public ChatClient(String clientId, KeyPair identityKey, SessionManager sessionManager, MessageRepository messageRepository, IdentityKeyStore keyStoreManager) {
        this.clientId = clientId;
        this.identityKey = identityKey;
        this.sessionManager = sessionManager;
        this.messageRepository = messageRepository;
        this.keyStoreManager = keyStoreManager;
    }

    public void connect(String host, int port) {
        if (connectionManager != null) return;
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
        if (currentPeerId == null) return null;
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
        
        try {
            // 1. Send HELLO with our Identity Public Key to initiate Trust On First Use
            Message helloMsg = new MessageBuilder()
                    .setType(MessageType.HELLO)
                    .setSenderId(clientId)
                    .setReceiverId(peerId)
                    .setPayload(identityKey.getPublic().getEncoded())
                    .setIv(new byte[0])
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
                    .setIv(new byte[0])
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
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PublicKey receivedKey = kf.generatePublic(new X509EncodedKeySpec(msg.getPayload()));
                
                Optional<PublicKey> existingKey = keyStoreManager.getPeerKey(msg.getSenderId());
                if (!existingKey.isPresent()) {
                    logger.info("TOFU: Storing new key for peer {}", msg.getSenderId());
                    keyStoreManager.storePeerKey(msg.getSenderId(), receivedKey);
                    
                    // Reply with our HELLO if we haven't already
                    Message helloReply = new MessageBuilder()
                            .setType(MessageType.HELLO)
                            .setSenderId(clientId)
                            .setReceiverId(msg.getSenderId())
                            .setPayload(identityKey.getPublic().getEncoded())
                            .setIv(new byte[0])
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
                            .setIv(new byte[0])
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
                                .setIv(new byte[0])
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
                        String plainText = new String(result.plaintext, StandardCharsets.UTF_8);
                        
                        messageRepository.saveMessage(msg.getSenderId(), msg.getReceiverId(), plainText, msg.getTimestamp());
                        
                        Message decryptedMsg = new MessageBuilder()
                                .setType(MessageType.TEXT_MESSAGE)
                                .setSenderId(msg.getSenderId())
                                .setReceiverId(msg.getReceiverId())
                                .setPayload(result.plaintext)
                                .setIv(msg.getIv())
                                .setMessageId(msg.getMessageId())
                                .setTimestamp(msg.getTimestamp())
                                .setSignature(msg.getSignature())
                                .buildUnsigned();
                        
                        for (MessageListener listener : listeners) {
                            listener.onMessageReceived(decryptedMsg);
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

    public void sendMessage(String text) {
        if (currentPeerId == null) return;
        Session session = sessionManager.getSession(currentPeerId);
        
        if (session.getState() != Session.State.ESTABLISHED) {
            logger.warn("Cannot send message, session not established");
            return;
        }
        
        try {
            long timestamp = System.currentTimeMillis();
            messageRepository.saveMessage(clientId, currentPeerId, text, timestamp);
            
            byte[] iv = sessionManager.generateIv(session, true);
            byte[] plaintext = text.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = AESUtils.encrypt(plaintext, session.getSecretKey(), iv);
            
            Message msg = new MessageBuilder()
                    .setType(MessageType.TEXT_MESSAGE)
                    .setSenderId(clientId)
                    .setReceiverId(currentPeerId)
                    .setPayload(ciphertext)
                    .setIv(iv)
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(timestamp)
                    .buildUnsigned();
                    
            Message signed = MessageSigner.sign(msg, identityKey.getPrivate());
            
            if (connectionManager != null) {
                connectionManager.sendMessage(signed);
            }
        } catch (Exception e) {
            logger.error("Error creating/sending encrypted message", e);
        }
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
