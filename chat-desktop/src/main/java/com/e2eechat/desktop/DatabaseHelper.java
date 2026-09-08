package com.e2eechat.desktop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates and migrates the local message store.
 *
 * <p>Migrations are additive and idempotent: each new column is added only if the live schema does
 * not already have it, so an existing {@code chat.db} written by an earlier build keeps its
 * history instead of being dropped.
 */
public class DatabaseHelper {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);

    public static void initializeDatabase(String dbPath) {
        String dbUrl = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE TABLE IF NOT EXISTS messages (\n"
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " sender TEXT NOT NULL,\n"
                    + " receiver TEXT NOT NULL,\n"
                    + " content TEXT NOT NULL,\n"
                    + " timestamp DATETIME DEFAULT CURRENT_TIMESTAMP\n"
                    + ");");

            Set<String> columns = existingColumns(stmt);

            // messageId: correlates delivery acknowledgements back to the row that produced them.
            if (!columns.contains("message_id")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN message_id TEXT");
            }
            // status: SENT / DELIVERED / READ / FAILED, drives the tick glyph.
            // Legacy rows may still have 'SENDING', which MessageRepository.readRow tolerates.
            if (!columns.contains("status")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN status TEXT DEFAULT 'SENT'");
            }
            // read_at: null while the message is unread, which is how the badge count is derived.
            if (!columns.contains("read_at")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN read_at INTEGER");
            }
            // Reply metadata, denormalised so rendering a quote needs no second query.
            if (!columns.contains("reply_to_id")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN reply_to_id TEXT");
            }
            if (!columns.contains("reply_to_sender")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN reply_to_sender TEXT");
            }
            if (!columns.contains("reply_to_preview")) {
                stmt.execute("ALTER TABLE messages ADD COLUMN reply_to_preview TEXT");
            }

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_participants "
                    + "ON messages(sender, receiver);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_timestamp "
                    + "ON messages(timestamp);");
            // Partial index: the unread count only ever scans rows where read_at is still null.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_unread "
                    + "ON messages(receiver, read_at) WHERE read_at IS NULL;");
            // A redelivered message must not produce a second row.
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_message_id "
                    + "ON messages(message_id) WHERE message_id IS NOT NULL;");

            logger.info("Database initialized at {}", dbPath);
        } catch (Exception e) {
            logger.error("Database initialization failed", e);
        }
    }

    private static Set<String> existingColumns(Statement stmt) throws Exception {
        Set<String> columns = new HashSet<>();
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(messages)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        return columns;
    }
}
