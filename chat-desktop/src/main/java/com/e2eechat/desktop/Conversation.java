package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;

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

    private String displayName;
    private int unreadCount;
    private boolean online;
    private boolean verified;
    private boolean pinned;
    private boolean muted;
    private boolean archived;

    public Conversation(String peerId, String lastMessage, long lastTimestamp,
                        boolean lastFromSelf, ChatMessage.Status lastStatus, int unreadCount) {
        this.peerId = peerId;
        this.lastMessage = lastMessage;
        this.lastTimestamp = lastTimestamp;
        this.lastFromSelf = lastFromSelf;
        this.lastStatus = lastStatus;
        this.unreadCount = unreadCount;
    }

    /** Peer id as it travels on the wire: 32 hex characters derived from the peer's identity key. */
    public String getPeerId() {
        return peerId;
    }

    /**
     * The label to show for this peer.
     *
     * <p>Peer ids no longer contain a name, so this is populated by the chat list from the peer
     * directory. Until that happens it falls back to a short form of the id, which is always
     * something the user can recognise.
     */
    public String getDisplayName() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        if (PeerId.isLegacyFormat(peerId)) {
            return peerId.substring(0, peerId.indexOf('@'));
        }
        return PeerId.shortForm(peerId);
    }

    /** Sets the resolved label; see {@link #getDisplayName()}. */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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

    /** Held at the top of the list, above everything else regardless of recency. */
    public boolean isPinned() {
        return pinned;
    }

    /** Still counted as unread, but never allowed to raise a notification. */
    public boolean isMuted() {
        return muted;
    }

    /** Filed out of the main list. The history is untouched. */
    public boolean isArchived() {
        return archived;
    }

    /** Applies this user's local choices, loaded by the chat list from {@link ConversationStore}. */
    public void applyState(ConversationStore.State state) {
        this.pinned = state.pinned;
        this.muted = state.muted;
        this.archived = state.archived;
    }
}
