package com.e2eechat.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MessageRepository {
    private static final Logger logger = LoggerFactory.getLogger(MessageRepository.class);
    private final String dbUrl;

    public MessageRepository(String dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
    }

    public void saveMessage(String sender, String receiver, String content, long timestamp) {
        String sql = "INSERT INTO messages (sender, receiver, content, timestamp) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sender);
            pstmt.setString(2, receiver);
            pstmt.setString(3, content);
            pstmt.setLong(4, timestamp);
            pstmt.executeUpdate();
        } catch (Exception e) {
            logger.error("Failed to save message", e);
        }
    }

    public List<ChatMessage> getMessages(String user1, String user2, int limit) {
        String sql = "SELECT sender, receiver, content, timestamp FROM messages " +
                     "WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) " +
                     "ORDER BY timestamp DESC LIMIT ?";
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
                    messages.add(new ChatMessage(
                            rs.getString("sender"),
                            rs.getString("receiver"),
                            rs.getString("content"),
                            rs.getLong("timestamp")
                    ));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve messages", e);
        }
        
        // We retrieved DESC to get latest limit items. We reverse to show chronologically in UI.
        Collections.reverse(messages);
        return messages;
    }
}
