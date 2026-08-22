package com.e2eechat.desktop;

/**
 * One row in the chat list: the peer, a preview of the most recent message, and unread state.
 *
 * <p>Assembled by {@link MessageRepository#getConversations(String)} in a single query so opening
 * the app does not fan out into one lookup per peer.
 */
public class Conversation {

    private final String peerId;
    private final String lastMessage;
    private final long lastTimestamp;
    private final boolean lastFromSelf;
    private final ChatMessage.Status lastStatus;

    private int unreadCount;
    private boolean online;
    private boolean verified;

    public Conversation(String peerId, String lastMessage, long lastTimestamp,
                        boolean lastFromSelf, ChatMessage.Status lastStatus, int unreadCount) {
        this.peerId = peerId;
        this.lastMessage = lastMessage;
        this.lastTimestamp = lastTimestamp;
        this.lastFromSelf = lastFromSelf;
        this.lastStatus = lastStatus;
        this.unreadCount = unreadCount;
    }

    /** Peer id as it travels on the wire, e.g. {@code alice@1a2b3c4d}. */
    public String getPeerId() {
        return peerId;
    }

    /** The leading component of the peer id, which is what Telegram would show as a name. */
    public String getDisplayName() {
        int at = peerId.indexOf('@');
        return at > 0 ? peerId.substring(0, at) : peerId;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public long getLastTimestamp() {
        return lastTimestamp;
    }

    public boolean isLastFromSelf() {
        return lastFromSelf;
    }

    public ChatMessage.Status getLastStatus() {
        return lastStatus;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    /** True once the user has compared safety numbers with this peer out of band. */
    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }
}
