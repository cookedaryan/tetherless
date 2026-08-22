package com.e2eechat.desktop;

public class ChatMessage {
    private final String sender;
    private final String receiver;
    private final String content;
    private final long timestamp;

    public ChatMessage(String sender, String receiver, String content, long timestamp) {
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getSender() { return sender; }
    public String getReceiver() { return receiver; }
    public String getContent() { return content; }
    public long getTimestamp() { return timestamp; }
}
