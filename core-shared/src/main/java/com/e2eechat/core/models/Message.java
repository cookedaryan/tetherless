package com.e2eechat.core.models;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        CONNECT,
        DISCONNECT,
        KEY_EXCHANGE_INIT,
        KEY_EXCHANGE_REPLY,
        TEXT_MESSAGE
    }

    private MessageType type;
    private String senderId;
    private String receiverId;
    private byte[] payload;
    private byte[] signature;

    public Message(MessageType type, String senderId, String receiverId, byte[] payload) {
        this.type = type;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.payload = payload;
    }

    public MessageType getType() { return type; }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
    public byte[] getPayload() { return payload; }
    
    public byte[] getSignature() { return signature; }
    public void setSignature(byte[] signature) { this.signature = signature; }
}
