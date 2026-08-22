package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.util.Redact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

public class ClientSession implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ClientSession.class);
    private static final int TIMEOUT_MS = 30000;

    private final Socket socket;
    private final ClientRegistry registry;
    
    private FrameReader in;
    private FrameWriter out;
    private String clientId;
    private int missedPings = 0;

    public ClientSession(Socket socket, ClientRegistry registry) {
        this.socket = socket;
        this.registry = registry;
    }

    public void sendMessage(Message message) throws IOException, com.e2eechat.core.protocol.ProtocolException {
        if (out != null) {
            out.writeMessage(message);
        }
    }

    public void disconnect() {
        try {
            socket.close();
        } catch (IOException e) {
            // Ignored
        }
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(TIMEOUT_MS);
            out = new FrameWriter(socket.getOutputStream());
            in = new FrameReader(socket.getInputStream());

            while (true) {
                Message message;
                try {
                    message = in.readMessage();
                    missedPings = 0; // Reset on any successful read
                } catch (SocketTimeoutException e) {
                    missedPings++;
                    if (missedPings >= 2) {
                        logger.info("Client {} timed out after {} missed pings", 
                                clientId != null ? Redact.id(clientId) : "unknown", missedPings);
                        break;
                    }
                    // Send PING
                    Message pingMsg = new MessageBuilder()
                            .setType(MessageType.PING)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    sendMessage(pingMsg);
                    continue;
                }

                if (message.getType() == MessageType.HELLO) {
                    if (clientId == null) {
                        clientId = message.getSenderId();
                        boolean registered = registry.register(clientId, this);
                        if (!registered) {
                            Message errorMsg = new MessageBuilder()
                                    .setType(MessageType.ERROR)
                                    .setSenderId("SERVER")
                                    .setReceiverId(clientId)
                                    .setMessageId(UUID.randomUUID().toString())
                                    .setPayload("Duplicate client ID".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .setTimestamp(System.currentTimeMillis())
                                    .buildUnsigned();
                            sendMessage(errorMsg);
                            break; // Disconnect
                        }
                    } else {
                        logger.warn("Received duplicate HELLO from already identified client: {}", Redact.id(clientId));
                    }
                } else if (message.getType() == MessageType.DISCONNECT) {
                    break;
                } else if (message.getType() == MessageType.PING) {
                    Message pongMsg = new MessageBuilder()
                            .setType(MessageType.PONG)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    sendMessage(pongMsg);
                } else if (message.getType() == MessageType.PONG) {
                    // Handled by missedPings reset above
                } else {
                    routeMessage(message);
                }
            }
        } catch (Exception e) {
            if (clientId != null) {
                logger.info("Client error or disconnect {}: {}", Redact.id(clientId), e.getMessage());
            } else {
                logger.info("Unknown client error or disconnect: {}", e.getMessage());
            }
        } finally {
            if (clientId != null) {
                registry.unregister(clientId, this);
            }
            disconnect();
        }
    }

    private void routeMessage(Message message) {
        String receiverId = message.getReceiverId();
        if (receiverId == null) {
            logger.warn("Message dropped (no receiver specified)");
            return;
        }

        ClientSession receiverSession = registry.lookup(receiverId);
        if (receiverSession != null) {
            try {
                receiverSession.sendMessage(message);
            } catch (Exception e) {
                logger.warn("Failed to route message to {}: {}", Redact.id(receiverId), e.getMessage());
            }
        } else {
            logger.warn("Receiver not found: {}", Redact.id(receiverId));
            try {
                Message errorMsg = new MessageBuilder()
                        .setType(MessageType.ERROR)
                        .setSenderId("SERVER")
                        .setReceiverId(clientId)
                        .setMessageId(UUID.randomUUID().toString())
                        .setPayload("RECIPIENT_OFFLINE".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned();
                sendMessage(errorMsg);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
