package com.e2eechat.desktop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseHelper {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);

    public static void initializeDatabase(String dbPath) {
        String dbUrl = "jdbc:sqlite:" + dbPath;
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            
            String sql = "CREATE TABLE IF NOT EXISTS messages (\n"
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " sender TEXT NOT NULL,\n"
                    + " receiver TEXT NOT NULL,\n"
                    + " content TEXT NOT NULL,\n"
                    + " timestamp DATETIME DEFAULT CURRENT_TIMESTAMP\n"
                    + ");";
            stmt.execute(sql);
            
            // Create index for fast querying
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_messages_participants ON messages(sender, receiver);");
            
            logger.info("Database initialized at {}", dbPath);
        } catch (Exception e) {
            logger.error("Database initialization failed", e);
        }
    }
}
