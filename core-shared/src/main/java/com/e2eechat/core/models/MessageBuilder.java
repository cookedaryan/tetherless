package com.e2eechat.core.models;

public class MessageBuilder {
    private MessageType type;
    private String senderId;
    private String receiverId;
    private byte[] payload;
    private byte[] signature;
    private String messageId;
    private long timestamp;
    private byte[] iv;
    private int protocolVersion = 1;

    public MessageBuilder setType(MessageType type) { this.type = type; return this; }
    public MessageBuilder setSenderId(String senderId) { this.senderId = senderId; return this; }
    public MessageBuilder setReceiverId(String receiverId) { this.receiverId = receiverId; return this; }
    public MessageBuilder setPayload(byte[] payload) { this.payload = payload; return this; }
    public MessageBuilder setSignature(byte[] signature) { this.signature = signature; return this; }
    public MessageBuilder setMessageId(String messageId) { this.messageId = messageId; return this; }
    public MessageBuilder setTimestamp(long timestamp) { this.timestamp = timestamp; return this; }
    public MessageBuilder setIv(byte[] iv) { this.iv = iv; return this; }
    public MessageBuilder setProtocolVersion(int protocolVersion) { this.protocolVersion = protocolVersion; return this; }

    public Message buildUnsigned() {
        validate(false);
        return new Message(type, senderId, receiverId, payload, signature, messageId, timestamp, iv, protocolVersion);
    }

    public Message build() {
        validate(true);
        return new Message(type, senderId, receiverId, payload, signature, messageId, timestamp, iv, protocolVersion);
    }

    private void validate(boolean checkSignature) {
        if (type == null) { throw new IllegalArgumentException("Type is required"); }
        if (type == MessageType.TEXT_MESSAGE && iv == null) {
            throw new IllegalArgumentException("IV is required for TEXT_MESSAGE");
        }
        if (type != MessageType.TEXT_MESSAGE && iv != null) {
            throw new IllegalArgumentException("IV is not allowed for " + type);
        }

        if (checkSignature) {
            boolean sigRequired = type == MessageType.HELLO ||
                    type == MessageType.KEY_EXCHANGE_INIT ||
                    type == MessageType.KEY_EXCHANGE_REPLY ||
                    type == MessageType.KEY_EXCHANGE_REJECT ||
                    type == MessageType.TEXT_MESSAGE;

            if (sigRequired && signature == null) {
                throw new IllegalArgumentException("Signature is required for " + type);
            }
        }
    }
}
