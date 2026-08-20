package com.e2eechat.core.models;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
public class Message {
    private final MessageType type;
    private final String senderId;
    private final String receiverId;
    private final byte[] payload;
    private final byte[] signature;
    private final String messageId;
    private final long timestamp;
    private final byte[] iv;
    private final int protocolVersion;
    Message(MessageType type, String senderId, String receiverId, byte[] payload, byte[] signature, String messageId, long timestamp, byte[] iv, int protocolVersion) {
        this.type = type;
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.payload = payload;
        this.signature = signature;
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.iv = iv;
        this.protocolVersion = protocolVersion;
    }
    public MessageType getType() { return type; }
    public String getSenderId() { return senderId; }
    public String getReceiverId() { return receiverId; }
    public byte[] getPayload() { return payload; }
    public byte[] getSignature() { return signature; }
    public String getMessageId() { return messageId; }
    public long getTimestamp() { return timestamp; }
    public byte[] getIv() { return iv; }
    public int getProtocolVersion() { return protocolVersion; }
    public byte[] canonicalBytesForSigning() {
        byte[] typeBytes = type.name().getBytes(StandardCharsets.UTF_8);
        byte[] msgIdBytes = messageId != null ? messageId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] senderBytes = senderId != null ? senderId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] receiverBytes = receiverId != null ? receiverId.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] ivBytes = iv != null ? iv : new byte[0];
        byte[] payloadBytes = payload != null ? payload : new byte[0];
        int length = 4 + typeBytes.length +
                     4 + msgIdBytes.length +
                     4 + senderBytes.length +
                     4 + receiverBytes.length +
                     8 +
                     4 + ivBytes.length +
                     4 + payloadBytes.length +
                     4;
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putInt(typeBytes.length).put(typeBytes);
        buffer.putInt(msgIdBytes.length).put(msgIdBytes);
        buffer.putInt(senderBytes.length).put(senderBytes);
        buffer.putInt(receiverBytes.length).put(receiverBytes);
        buffer.putLong(timestamp);
        buffer.putInt(ivBytes.length).put(ivBytes);
        buffer.putInt(payloadBytes.length).put(payloadBytes);
        buffer.putInt(protocolVersion);
        return buffer.array();
    }
}
