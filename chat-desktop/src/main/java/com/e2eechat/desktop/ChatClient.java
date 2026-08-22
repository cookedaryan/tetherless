package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import java.net.Socket;


import org.slf4j.Logger; import org.slf4j.LoggerFactory; public class ChatClient { private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);
    
    private String clientId;
    private String receiverId;
    private ChatWindow window;
    private FrameWriter out;
    private FrameReader in;

    public ChatClient(String clientId, String receiverId) {
        this.clientId = clientId;
        this.receiverId = receiverId;
    }
    
    public void setWindow(ChatWindow window) {
        this.window = window;
    }

    public void connect(String host, int port) {
        try {
            Socket socket = new Socket(host, port);
            out = new FrameWriter(socket.getOutputStream());
            in = new FrameReader(socket.getInputStream());
            
            out.writeMessage(new com.e2eechat.core.models.MessageBuilder().setType(com.e2eechat.core.models.MessageType.HELLO).setSenderId(clientId).setMessageId(java.util.UUID.randomUUID().toString()).setTimestamp(System.currentTimeMillis()).buildUnsigned());
            
            
            new Thread(() -> {
                try {
                    while (true) {
                        Message msg = in.readMessage();
                        if (msg.getType() == com.e2eechat.core.models.MessageType.TEXT_MESSAGE) {
                            // In real app, we would decrypt msg.getPayload() here using AESUtils
                            String text = new String(msg.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
                            if (window != null) {
                                window.appendMessage(msg.getSenderId() + ": " + text);
                            }
                        } else if (msg.getType() == com.e2eechat.core.models.MessageType.PING) {
                            Message pongMsg = new com.e2eechat.core.models.MessageBuilder()
                                    .setType(com.e2eechat.core.models.MessageType.PONG)
                                    .setMessageId(java.util.UUID.randomUUID().toString())
                                    .setTimestamp(System.currentTimeMillis())
                                    .buildUnsigned();
                            out.writeMessage(pongMsg);
                        } else if (msg.getType() == com.e2eechat.core.models.MessageType.DISCONNECT) {
                            logger.info("Server initiated disconnect");
                            break;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error", e);
                }
            }).start();
        } catch (Exception e) {
            logger.error("Error", e);
        }
    }

    public void disconnect() {
        if (out != null) {
            try {
                Message disconnectMsg = new com.e2eechat.core.models.MessageBuilder()
                        .setType(com.e2eechat.core.models.MessageType.DISCONNECT)
                        .setSenderId(clientId)
                        .setMessageId(java.util.UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned();
                out.writeMessage(disconnectMsg);
            } catch (Exception e) {
                logger.warn("Error sending DISCONNECT", e);
            }
        }
    }

    public void sendMessage(String text) {
        try {
            // In real app, we would encrypt text here using AESUtils
            Message msg = new com.e2eechat.core.models.MessageBuilder().setType(com.e2eechat.core.models.MessageType.TEXT_MESSAGE).setSenderId(clientId).setReceiverId(receiverId).setPayload(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).setIv(new byte[12]).setMessageId(java.util.UUID.randomUUID().toString()).setTimestamp(System.currentTimeMillis()).buildUnsigned();
            out.writeMessage(msg);
            
        } catch (Exception e) {
            logger.error("Error", e);
        }
    }
}

