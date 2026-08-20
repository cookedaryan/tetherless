package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger; import org.slf4j.LoggerFactory; public class ChatClient { private static final Logger logger = LoggerFactory.getLogger(ChatClient.class);
    @SuppressFBWarnings("SECOBDES")
    private String clientId;
    private String receiverId;
    private ChatWindow window;
    private ObjectOutputStream out;
    private ObjectInputStream in;

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
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            
            out.writeObject(new com.e2eechat.core.models.MessageBuilder().setType(com.e2eechat.core.models.MessageType.HELLO).setSenderId(clientId).setMessageId(java.util.UUID.randomUUID().toString()).setTimestamp(System.currentTimeMillis()).buildUnsigned());
            out.flush();
            
            new Thread(() -> {
                try {
                    while (true) {
                        Message msg = (Message) in.readObject();
                        if (msg.getType() == com.e2eechat.core.models.MessageType.TEXT_MESSAGE) {
                            // In real app, we would decrypt msg.getPayload() here using AESUtils
                            String text = new String(msg.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
                            if (window != null) {
                                window.appendMessage(msg.getSenderId() + ": " + text);
                            }
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

    public void sendMessage(String text) {
        try {
            // In real app, we would encrypt text here using AESUtils
            Message msg = new com.e2eechat.core.models.MessageBuilder().setType(com.e2eechat.core.models.MessageType.TEXT_MESSAGE).setSenderId(clientId).setReceiverId(receiverId).setPayload(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)).setIv(new byte[12]).setMessageId(java.util.UUID.randomUUID().toString()).setTimestamp(System.currentTimeMillis()).buildUnsigned();
            out.writeObject(msg);
            out.flush();
        } catch (Exception e) {
            logger.error("Error", e);
        }
    }
}
