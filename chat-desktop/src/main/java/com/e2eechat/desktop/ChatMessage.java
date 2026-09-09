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

    /**
     * Position on the delivery ladder, or 0 for a status that is not on it.
     *
     * <p>Only PENDING -> SENT -> DELIVERED -> READ is ordered. FAILED sits off the ladder: a
     * message that failed and later goes out has to be able to become SENT again, or it keeps a
     * warning it has outgrown.
     */
    public static int ladderPosition(Status status) {
        if (status == null) {
            return 0;
        }
        switch (status) {
            case PENDING:
                return 1;
            case SENT:
                return 2;
            case DELIVERED:
                return 3;
            case READ:
                return 4;
            default:
                return 0;
        }
    }

    /**
     * Whether moving from {@code from} to {@code to} would walk back down the ladder.
     *
     * <p>It happens in ordinary use: this client re-acknowledges on every read receipt and the
     * relay may redeliver, so a {@code DELIVERY_ACK} can arrive after a read receipt has already
     * been applied. Applying it would pull a filled double tick back to a plain one, which the
     * sender reads as the peer un-reading their message.
     *
     * <p>Defined here, next to the states themselves, because the rule has to hold in two places
     * at once - the open transcript and the database behind it. It held only in the transcript,
     * so a downgrade the window refused was still written to disk and came back on restart.
     */
    public static boolean isStatusRegression(Status from, Status to) {
        int wasAt = ladderPosition(from);
        int goingTo = ladderPosition(to);
        return wasAt > 0 && goingTo > 0 && goingTo <= wasAt;
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
