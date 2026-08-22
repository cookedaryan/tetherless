package com.e2eechat.desktop;

/**
 * One rendered message in a transcript.
 *
 * <p>Immutable except for {@link #status}, which advances as delivery acknowledgements arrive from
 * the peer. Everything the bubble needs to paint itself lives here so the renderer never has to
 * reach back into the repository while the UI thread is painting.
 */
public class ChatMessage {

    /** Delivery progress, mirrored by the tick glyph in the corner of an outgoing bubble. */
    public enum Status {
        /** Queued locally; the relay has not seen it. Rendered as a clock. */
        SENDING,
        /** Handed to the relay. Rendered as a single tick. */
        SENT,
        /** The peer's client acknowledged receipt. Rendered as a double tick. */
        DELIVERED,
        /** The peer opened the conversation. Rendered as a filled double tick. */
        READ,
        /** The send failed and will not be retried automatically. Rendered as a warning. */
        FAILED
    }

    private final String messageId;
    private final String sender;
    private final String receiver;
    private final String content;
    private final long timestamp;
    private final String replyToId;
    private final String replyToSender;
    private final String replyToPreview;

    private Status status;

    public ChatMessage(String sender, String receiver, String content, long timestamp) {
        this(null, sender, receiver, content, timestamp, Status.SENT, null, null, null);
    }

    public ChatMessage(String messageId, String sender, String receiver, String content,
                       long timestamp, Status status) {
        this(messageId, sender, receiver, content, timestamp, status, null, null, null);
    }

    public ChatMessage(String messageId, String sender, String receiver, String content,
                       long timestamp, Status status,
                       String replyToId, String replyToSender, String replyToPreview) {
        this.messageId = messageId;
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.timestamp = timestamp;
        this.status = status == null ? Status.SENT : status;
        this.replyToId = replyToId;
        this.replyToSender = replyToSender;
        this.replyToPreview = replyToPreview;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSender() {
        return sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getReplyToId() {
        return replyToId;
    }

    public String getReplyToSender() {
        return replyToSender;
    }

    public String getReplyToPreview() {
        return replyToPreview;
    }

    public boolean hasReply() {
        return replyToId != null && replyToPreview != null;
    }

    /** True when the content is a decryption or authentication failure marker, not real text. */
    public boolean isError() {
        return content != null
                && (content.contains("[Decryption Failed]") || content.startsWith("[Error:"));
    }
}
