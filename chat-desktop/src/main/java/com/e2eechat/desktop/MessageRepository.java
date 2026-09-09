package com.e2eechat.desktop;

import com.e2eechat.core.crypto.AESUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes the local message store.
 *
 * <p>Message bodies are encrypted at rest with a passphrase-derived key, so any operation that
 * needs to look at text — previews for the chat list, full-text search — has to decrypt in Java
 * rather than push the work into SQL. That is a deliberate trade: it costs a linear scan, and it
 * keeps message content off disk in the clear. Volumes here are small enough that the scan is not
 * worth optimising away.
 */
public class MessageRepository {
    private static final Logger logger = LoggerFactory.getLogger(MessageRepository.class);

    private final String dbUrl;
    private final SecretKey dbKey;

    public MessageRepository(String dbPath, SecretKey dbKey) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        this.dbKey = dbKey;
    }

    // ------------------------------------------------------------------ write

    public void saveMessage(String sender, String receiver, String content, long timestamp) {
        saveMessage(null, sender, receiver, content, timestamp,
                ChatMessage.Status.SENT, null, null, null, false);
    }

    /**
     * Inserts a message.
     *
     * @param markRead true for messages the local user sent or has already seen; false leaves the
     *                 row counting towards the peer's unread badge
     */
    public void saveMessage(String messageId, String sender, String receiver, String content,
                            long timestamp, ChatMessage.Status status,
                            String replyToId, String replyToSender, String replyToPreview,
                            boolean markRead) {
        String sql = "INSERT OR IGNORE INTO messages "
                + "(message_id, sender, receiver, content, timestamp, status, read_at, "
                + " reply_to_id, reply_to_sender, reply_to_preview) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, messageId);
            pstmt.setString(2, sender);
            pstmt.setString(3, receiver);
            pstmt.setString(4, encrypt(content));
            pstmt.setLong(5, timestamp);
            pstmt.setString(6, status.name());
            if (markRead) {
                pstmt.setLong(7, System.currentTimeMillis());
            } else {
                pstmt.setNull(7, java.sql.Types.INTEGER);
            }
            pstmt.setString(8, replyToId);
            pstmt.setString(9, replyToSender);
            pstmt.setString(10, replyToPreview == null ? null : encrypt(replyToPreview));
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to save message", e);
        }
    }

    /** Advances a message's delivery state. No-op if the id is unknown. */
    public void updateStatus(String messageId, ChatMessage.Status status) {
        if (messageId == null) {
            return;
        }
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE messages SET status = ? WHERE message_id = ?")) {
            pstmt.setString(1, status.name());
            pstmt.setString(2, messageId);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to update message status", e);
        }
    }

    /** Clears the unread badge for one peer. Returns the number of rows it cleared. */
    public int markConversationRead(String localClientId, String peerId) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE messages SET read_at = ? "
                             + "WHERE receiver = ? AND sender = ? AND read_at IS NULL")) {
            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.setString(2, localClientId);
            pstmt.setString(3, peerId);
            return pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to mark conversation read", e);
            return 0;
        }
    }

    /** Marks every outgoing message to a peer as read, on receiving their read receipt. */
    public void markOutgoingRead(String localClientId, String peerId) {
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE messages SET status = 'READ' "
                             + "WHERE sender = ? AND receiver = ? AND status IN ('SENT','DELIVERED')")) {
            pstmt.setString(1, localClientId);
            pstmt.setString(2, peerId);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to mark outgoing messages read", e);
        }
    }

    // ------------------------------------------------------------------- read

    public List<ChatMessage> getMessages(String user1, String user2, int limit) {
        String sql = "SELECT message_id, sender, receiver, content, timestamp, status, "
                + "       reply_to_id, reply_to_sender, reply_to_preview FROM messages "
                + "WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) "
                + "ORDER BY timestamp DESC LIMIT ?";
        List<ChatMessage> messages = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, user1);
            pstmt.setString(2, user2);
            pstmt.setString(3, user2);
            pstmt.setString(4, user1);
            pstmt.setInt(5, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(readRow(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve messages", e);
        }

        Collections.reverse(messages);
        return messages;
    }

    /**
     * The outbox: everything this user has written to {@code peerId} that never reached the relay,
     * oldest first so a flush replays them in the order they were typed.
     */
    public List<ChatMessage> getPending(String self, String peerId) {
        String sql = "SELECT message_id, sender, receiver, content, timestamp, status, "
                + "       reply_to_id, reply_to_sender, reply_to_preview FROM messages "
                + "WHERE sender = ? AND receiver = ? AND status = 'PENDING' "
                + "ORDER BY timestamp ASC";
        List<ChatMessage> messages = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, self);
            pstmt.setString(2, peerId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(readRow(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve pending messages", e);
        }
        return messages;
    }

    /** Every peer this user has something queued for, so a reconnect can drain the lot. */
    public List<String> getPendingPeers(String self) {
        String sql = "SELECT DISTINCT receiver FROM messages "
                + "WHERE sender = ? AND status = 'PENDING'";
        List<String> peers = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, self);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    peers.add(rs.getString("receiver"));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve peers with pending messages", e);
        }
        return peers;
    }

    private ChatMessage readRow(ResultSet rs) throws Exception {
        String statusName = rs.getString("status");
        ChatMessage.Status status;
        try {
            status = statusName == null
                    ? ChatMessage.Status.SENT
                    : ChatMessage.Status.valueOf(statusName);
        } catch (IllegalArgumentException e) {
            status = ChatMessage.Status.SENT;
        }
        String replyPreview = rs.getString("reply_to_preview");
        return new ChatMessage(
                rs.getString("message_id"),
                rs.getString("sender"),
                rs.getString("receiver"),
                decrypt(rs.getString("content")),
                rs.getLong("timestamp"),
                status,
                rs.getString("reply_to_id"),
                rs.getString("reply_to_sender"),
                replyPreview == null ? null : decrypt(replyPreview));
    }

    /**
     * One row per peer, newest first, carrying the last message and the unread count.
     *
     * <p>Uses a window function to pick each peer's newest row in a single pass rather than issuing
     * a query per peer.
     */
    public List<Conversation> getConversations(String localClientId) {
        String sql = "SELECT peer, content, timestamp, sender, status FROM ("
                + "  SELECT CASE WHEN sender = ? THEN receiver ELSE sender END AS peer, "
                + "         content, timestamp, sender, status, "
                + "         ROW_NUMBER() OVER ("
                + "             PARTITION BY CASE WHEN sender = ? THEN receiver ELSE sender END "
                + "             ORDER BY timestamp DESC) AS rn "
                + "  FROM messages WHERE sender = ? OR receiver = ?"
                + ") WHERE rn = 1 ORDER BY timestamp DESC";

        List<Conversation> conversations = new ArrayList<>();
        Map<String, Integer> unread = getUnreadCounts(localClientId);

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 1; i <= 4; i++) {
                pstmt.setString(i, localClientId);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String peer = rs.getString("peer");
                    String statusName = rs.getString("status");
                    ChatMessage.Status status;
                    try {
                        status = statusName == null
                                ? ChatMessage.Status.SENT
                                : ChatMessage.Status.valueOf(statusName);
                    } catch (IllegalArgumentException e) {
                        status = ChatMessage.Status.SENT;
                    }
                    conversations.add(new Conversation(
                            peer,
                            decrypt(rs.getString("content")),
                            rs.getLong("timestamp"),
                            localClientId.equals(rs.getString("sender")),
                            status,
                            unread.getOrDefault(peer, 0)));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve conversations", e);
        }
        return conversations;
    }

    private Map<String, Integer> getUnreadCounts(String localClientId) {
        Map<String, Integer> counts = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT sender, COUNT(*) AS n FROM messages "
                             + "WHERE receiver = ? AND read_at IS NULL GROUP BY sender")) {
            pstmt.setString(1, localClientId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("sender"), rs.getInt("n"));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve unread counts", e);
        }
        return counts;
    }

    /**
     * Case-insensitive substring search across every message the local user can see.
     *
     * <p>Decrypts row by row because the ciphertext is opaque to SQL. Capped at {@code limit} hits.
     */
    public List<ChatMessage> searchMessages(String localClientId, String query, int limit) {
        List<ChatMessage> hits = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) {
            return hits;
        }
        String needle = query.toLowerCase(Locale.ROOT);

        String sql = "SELECT message_id, sender, receiver, content, timestamp, status, "
                + "       reply_to_id, reply_to_sender, reply_to_preview FROM messages "
                + "WHERE sender = ? OR receiver = ? ORDER BY timestamp DESC";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, localClientId);
            pstmt.setString(2, localClientId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next() && hits.size() < limit) {
                    ChatMessage m = readRow(rs);
                    if (m.getContent() != null
                            && m.getContent().toLowerCase(Locale.ROOT).contains(needle)) {
                        hits.add(m);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to search messages", e);
        }
        return hits;
    }

    public List<String> getKnownPeers(String localClientId) {
        String sql = "SELECT DISTINCT sender AS peer FROM messages WHERE receiver = ? "
                + "UNION SELECT DISTINCT receiver AS peer FROM messages WHERE sender = ?";
        List<String> peers = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, localClientId);
            pstmt.setString(2, localClientId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    peers.add(rs.getString("peer"));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve known peers", e);
        }
        return peers;
    }

    // ------------------------------------------------------------ at-rest crypto

    /**
     * Re-encrypts every stored body from {@code from} to {@code to}, for changing the key
     * derivation without losing history.
     *
     * <p>Runs as a single transaction: an interrupted migration rolls back rather than leaving some
     * rows readable under the old key and some under the new, which would be unrecoverable.
     *
     * @return the number of rows re-encrypted, or -1 if the migration failed and was rolled back
     */
    public int migrateEncryption(SecretKey from, SecretKey to) {
        String select = "SELECT id, content, reply_to_preview FROM messages";
        String update = "UPDATE messages SET content = ?, reply_to_preview = ? WHERE id = ?";

        try (Connection conn = DriverManager.getConnection(dbUrl)) {
            conn.setAutoCommit(false);
            int migrated = 0;
            try (PreparedStatement read = conn.prepareStatement(select);
                 PreparedStatement write = conn.prepareStatement(update);
                 ResultSet rs = read.executeQuery()) {

                while (rs.next()) {
                    long id = rs.getLong("id");
                    String content = rs.getString("content");
                    String replyPreview = rs.getString("reply_to_preview");

                    // A row that will not decrypt under the old key cannot be migrated; failing the
                    // whole transaction is safer than silently replacing it with a marker.
                    String plainContent = decryptWith(content, from);
                    String plainPreview = replyPreview == null
                            ? null : decryptWith(replyPreview, from);

                    write.setString(1, encryptWith(plainContent, to));
                    write.setString(2, plainPreview == null ? null : encryptWith(plainPreview, to));
                    write.setLong(3, id);
                    write.addBatch();
                    migrated++;
                }
                write.executeBatch();
                conn.commit();
                logger.info("Re-encrypted {} stored messages under the new key derivation", migrated);
                return migrated;
            } catch (Exception e) {
                conn.rollback();
                logger.error("Re-encryption failed and was rolled back; the database is unchanged", e);
                return -1;
            }
        } catch (Exception e) {
            logger.error("Could not open the database to re-encrypt it", e);
            return -1;
        }
    }

    private static String encryptWith(String content, SecretKey key) throws Exception {
        byte[] iv = AESUtils.generateIV();
        byte[] ciphertext = AESUtils.encrypt(content.getBytes(StandardCharsets.UTF_8), key, iv);
        ByteBuffer bb = ByteBuffer.allocate(iv.length + ciphertext.length);
        bb.put(iv);
        bb.put(ciphertext);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private static String decryptWith(String encoded, SecretKey key) throws Exception {
        byte[] payload = Base64.getDecoder().decode(encoded);
        if (payload.length <= 12) {
            throw new IllegalStateException("stored payload is too short to be a message");
        }
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[payload.length - 12];
        System.arraycopy(payload, 0, iv, 0, 12);
        System.arraycopy(payload, 12, ciphertext, 0, ciphertext.length);
        return new String(AESUtils.decrypt(ciphertext, key, iv), StandardCharsets.UTF_8);
    }

    private String encrypt(String content) throws Exception {
        byte[] plaintext = content.getBytes(StandardCharsets.UTF_8);
        byte[] iv = AESUtils.generateIV();
        byte[] ciphertext = AESUtils.encrypt(plaintext, dbKey, iv);

        ByteBuffer bb = ByteBuffer.allocate(iv.length + ciphertext.length);
        bb.put(iv);
        bb.put(ciphertext);
        return Base64.getEncoder().encodeToString(bb.array());
    }

    private String decrypt(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length <= 12) {
                return "[Error: Payload too short]";
            }
            byte[] iv = new byte[12];
            byte[] ciphertext = new byte[payload.length - 12];
            System.arraycopy(payload, 0, iv, 0, 12);
            System.arraycopy(payload, 12, ciphertext, 0, ciphertext.length);
            return new String(AESUtils.decrypt(ciphertext, dbKey, iv), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not Base64 at all: a plaintext row written before at-rest encryption landed.
            return encoded;
        } catch (Exception e) {
            logger.error("Failed to decrypt message content", e);
            return "[Decryption Failed]";
        }
    }
}
