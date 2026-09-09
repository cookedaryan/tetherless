package com.e2eechat.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Whether a conversation is pinned, muted or archived.
 *
 * <p>Local to this profile and invisible to the wire. Muting somebody is a statement about your own
 * notifications, not about them, and nothing here is ever sent - a peer cannot discover they have
 * been muted or archived, which is the only sane way for a feature like this to behave.
 *
 * <p>Kept apart from {@link MessageRepository} because it is not about messages: the state outlives
 * the history, so clearing a conversation leaves its pin in place.
 */
public class ConversationStore {

    private static final Logger logger = LoggerFactory.getLogger(ConversationStore.class);

    /** One conversation's flags. Immutable; a change writes a new row. */
    public static final class State {
        public final boolean pinned;
        public final boolean muted;
        public final boolean archived;

        public State(boolean pinned, boolean muted, boolean archived) {
            this.pinned = pinned;
            this.muted = muted;
            this.archived = archived;
        }

        static final State NONE = new State(false, false, false);

        State withPinned(boolean value) {
            return new State(value, muted, archived);
        }

        State withMuted(boolean value) {
            return new State(pinned, value, archived);
        }

        State withArchived(boolean value) {
            return new State(pinned, muted, value);
        }
    }

    private final String dbUrl;

    public ConversationStore(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
    }

    /** Every peer with something set, in one query, so building the list is not N lookups. */
    public Map<String, State> loadAll() {
        Map<String, State> states = new HashMap<>();
        String sql = "SELECT peer, pinned, muted, archived FROM conversation_state";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                states.put(rs.getString("peer"), new State(
                        rs.getInt("pinned") != 0,
                        rs.getInt("muted") != 0,
                        rs.getInt("archived") != 0));
            }
        } catch (Exception e) {
            logger.error("Failed to load conversation state", e);
        }
        return states;
    }

    public State get(String peerId) {
        String sql = "SELECT pinned, muted, archived FROM conversation_state WHERE peer = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, peerId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new State(rs.getInt("pinned") != 0, rs.getInt("muted") != 0,
                            rs.getInt("archived") != 0);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to read conversation state", e);
        }
        return State.NONE;
    }

    public void setPinned(String peerId, boolean value) {
        write(peerId, get(peerId).withPinned(value));
    }

    public void setMuted(String peerId, boolean value) {
        write(peerId, get(peerId).withMuted(value));
    }

    /** Archiving also unpins: a chat cannot be both filed away and held at the top of the list. */
    public void setArchived(String peerId, boolean value) {
        State current = get(peerId).withArchived(value);
        write(peerId, value ? current.withPinned(false) : current);
    }

    /** Forgets everything about a conversation, for when its history is deleted too. */
    public void clear(String peerId) {
        String sql = "DELETE FROM conversation_state WHERE peer = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, peerId);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to clear conversation state", e);
        }
    }

    private void write(String peerId, State state) {
        String sql = "INSERT INTO conversation_state (peer, pinned, muted, archived) "
                + "VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(peer) DO UPDATE SET pinned = ?, muted = ?, archived = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, peerId);
            pstmt.setInt(2, state.pinned ? 1 : 0);
            pstmt.setInt(3, state.muted ? 1 : 0);
            pstmt.setInt(4, state.archived ? 1 : 0);
            pstmt.setInt(5, state.pinned ? 1 : 0);
            pstmt.setInt(6, state.muted ? 1 : 0);
            pstmt.setInt(7, state.archived ? 1 : 0);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to write conversation state", e);
        }
    }
}
