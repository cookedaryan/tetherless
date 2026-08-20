package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ChatClient {
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
            
            out.writeObject(new Message(Message.MessageType.CONNECT, clientId, null, null));
            out.flush();
            
            new Thread(() -> {
                try {
                    while (true) {
                        Message msg = (Message) in.readObject();
                        if (msg.getType() == Message.MessageType.TEXT_MESSAGE) {
                            // In real app, we would decrypt msg.getPayload() here using AESUtils
                            String text = new String(msg.getPayload());
                            if (window != null) {
                                window.appendMessage(msg.getSenderId() + ": " + text);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(String text) {
        try {
            // In real app, we would encrypt text here using AESUtils
            Message msg = new Message(Message.MessageType.TEXT_MESSAGE, clientId, receiverId, text.getBytes());
            out.writeObject(msg);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
