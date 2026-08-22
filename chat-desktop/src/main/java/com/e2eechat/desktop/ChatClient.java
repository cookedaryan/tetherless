package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatClient implements MessageListener {
    private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);
    
    private final String clientId;
    private final String receiverId;
    
    private ConnectionManager connectionManager;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();
    
    // Buffer messages that arrive before any listener is registered
    private final List<Message> earlyMessageBuffer = new ArrayList<>();

    public ChatClient(String clientId, String receiverId) {
        this.clientId = clientId;
        this.receiverId = receiverId;
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
        
        // Drain early messages to the new listener
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

    @Override
    public void onMessageReceived(Message msg) {
        if (listeners.isEmpty()) {
            synchronized (earlyMessageBuffer) {
                earlyMessageBuffer.add(msg);
            }
            return;
        }
        
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

    public void sendMessage(String text) {
        try {
            // In real app, we would encrypt text here using AESUtils
            Message msg = new MessageBuilder()
                    .setType(MessageType.TEXT_MESSAGE)
                    .setSenderId(clientId)
                    .setReceiverId(receiverId)
                    .setPayload(text.getBytes(StandardCharsets.UTF_8))
                    .setIv(new byte[12])
                    .setMessageId(UUID.randomUUID().toString())
                    .setTimestamp(System.currentTimeMillis())
                    .buildUnsigned();
            
            if (connectionManager != null) {
                connectionManager.sendMessage(msg);
            }
        } catch (Exception e) {
            logger.error("Error creating message", e);
        }
    }
    
    public String getClientId() {
        return clientId;
    }
    
    public String getReceiverId() {
        return receiverId;
    }
}
