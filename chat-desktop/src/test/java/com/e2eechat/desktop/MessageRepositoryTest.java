package com.e2eechat.desktop;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MessageRepositoryTest {

    private MessageRepository repository;
    private String dbUrl;
    private Connection keepAliveConnection;

    @Before
    public void setUp() throws Exception {
        // Use a unique shared memory database for each test
        String dbName = "memdb_" + UUID.randomUUID().toString().replace("-", "");
        String dbPath = "file:" + dbName + "?mode=memory&cache=shared";
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        
        // Keep at least one connection open so the in-memory DB is not destroyed
        keepAliveConnection = DriverManager.getConnection(dbUrl);
        
        // Build the schema through the production migration path so the fixture always has
        // whatever columns the repository currently writes.
        DatabaseHelper.initializeDatabase(dbPath);
        
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) i;
        }
        SecretKey dbKey = new SecretKeySpec(keyBytes, "AES");
        
        repository = new MessageRepository(dbPath, dbKey);
    }

    @After
    public void tearDown() throws Exception {
        if (keepAliveConnection != null) {
            keepAliveConnection.close();
        }
    }

    @Test
    public void testEncryptionAndPersistence() throws Exception {
        repository.saveMessage("Alice", "Bob", "Hello Bob, this is a secret!", 123456L);
        
        // Read raw column to ensure it is encrypted
        try (Statement stmt = keepAliveConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT content FROM messages WHERE sender='Alice'")) {
            assertTrue(rs.next());
            String rawContent = rs.getString("content");
            assertNotEquals("Hello Bob, this is a secret!", rawContent);
            assertFalse(rawContent.contains("Hello"));
        }
        
        // Retrieve through repository and verify decryption
        List<ChatMessage> messages = repository.getMessages("Alice", "Bob", 10);
        assertEquals(1, messages.size());
        assertEquals("Hello Bob, this is a secret!", messages.get(0).getContent());
        assertEquals("Alice", messages.get(0).getSender());
        assertEquals("Bob", messages.get(0).getReceiver());
    }

    @Test
    public void testGetKnownPeers() {
        repository.saveMessage("Alice", "Bob", "Hi Bob", 100);
        repository.saveMessage("Charlie", "Alice", "Hi Alice", 101);
        repository.saveMessage("Alice", "Dave", "Hi Dave", 102);
        
        List<String> peers = repository.getKnownPeers("Alice");
        assertEquals(3, peers.size());
        assertTrue(peers.contains("Bob"));
        assertTrue(peers.contains("Charlie"));
        assertTrue(peers.contains("Dave"));
    }
}
