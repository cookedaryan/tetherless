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
import java.util.List;

public class MessageRepository {
    private static final Logger logger = LoggerFactory.getLogger(MessageRepository.class);
    private final String dbUrl;
    private final SecretKey dbKey;

    public MessageRepository(String dbPath, SecretKey dbKey) {
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        this.dbKey = dbKey;
    }

    public void saveMessage(String sender, String receiver, String content, long timestamp) {
        String sql = "INSERT INTO messages (sender, receiver, content, timestamp) VALUES (?, ?, ?, ?)";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            // Encrypt the content
            byte[] plaintext = content.getBytes(StandardCharsets.UTF_8);
            byte[] iv = AESUtils.generateIV();
            byte[] ciphertext = AESUtils.encrypt(plaintext, dbKey, iv);
            
            // Concat IV and Ciphertext and Base64 encode
            ByteBuffer bb = ByteBuffer.allocate(iv.length + ciphertext.length);
            bb.put(iv);
            bb.put(ciphertext);
            String encodedContent = Base64.getEncoder().encodeToString(bb.array());
            
            pstmt.setString(1, sender);
            pstmt.setString(2, receiver);
            pstmt.setString(3, encodedContent);
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
                    String sender = rs.getString("sender");
                    String receiver = rs.getString("receiver");
                    String encodedContent = rs.getString("content");
                    long timestamp = rs.getLong("timestamp");
                    
                    String decodedContent;
                    try {
                        byte[] payload = Base64.getDecoder().decode(encodedContent);
                        if (payload.length > 12) {
                            byte[] iv = new byte[12];
                            byte[] ciphertext = new byte[payload.length - 12];
                            System.arraycopy(payload, 0, iv, 0, 12);
                            System.arraycopy(payload, 12, ciphertext, 0, ciphertext.length);
                            byte[] plaintext = AESUtils.decrypt(ciphertext, dbKey, iv);
                            decodedContent = new String(plaintext, StandardCharsets.UTF_8);
                        } else {
                            decodedContent = "[Error: Payload too short]";
                        }
                    } catch (IllegalArgumentException e) {
                        // Fallback for previously unencrypted rows in dev DBs
                        decodedContent = encodedContent;
                    } catch (Exception e) {
                        logger.error("Failed to decrypt message content", e);
                        decodedContent = "[Decryption Failed]";
                    }
                    
                    messages.add(new ChatMessage(sender, receiver, decodedContent, timestamp));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to retrieve messages", e);
        }
        
        Collections.reverse(messages);
        return messages;
    }
}
