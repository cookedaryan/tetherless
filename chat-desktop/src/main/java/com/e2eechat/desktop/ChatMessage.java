package com.e2eechat.desktop;

/**
 * One rendered message in a transcript.
 *
 * <p>Immutable except for {@link #status}, which advances as delivery acknowledgements arrive from
 * the peer. Everything the bubble needs to paint itself lives here so the renderer never has to
 * reach back into the repository while the UI thread is painting.
 */
public class ChatMessage {

    /**
     * Delivery progress, mirrored by the tick glyph in the corner of an outgoing bubble.
     *
     * <p>Persisted by {@linkplain Enum#name() name} rather than ordinal, so this list can be added
     * to without migrating anyone's history.
     */
    public enum Status {
        /**
         * Written down but not yet handed to the relay, because the transport was down or no
         * session with the peer had been established. Rendered as a clock, and retried on its own.
         *
         * <p>This state was deliberately absent until there was an outbox to give it meaning. A
         * message used to be dropped outright when no session existed - nothing sent, no row
         * written, and the text simply gone from the window it was typed into.
         */
        PENDING,
        /** Handed to the relay. Rendered as a single tick. */
        SENT,
        /** The peer's client acknowledged receipt. Rendered as a double tick. */
        DELIVERED,
        /** The peer opened the conversation. Rendered as a filled double tick. */
        READ,
        /** The send failed for a reason retrying will not fix. Rendered as a warning. */
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
