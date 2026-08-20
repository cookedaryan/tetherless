package com.e2eechat.desktop;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.slf4j.Logger; import org.slf4j.LoggerFactory; public class DatabaseHelper { private static final Logger logger = LoggerFactory.getLogger(DatabaseHelper.class);
    private static final String DB_URL = "jdbc:sqlite:chat.db";

    public static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            String sql = "CREATE TABLE IF NOT EXISTS messages (\n"
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + " sender TEXT NOT NULL,\n"
                    + " receiver TEXT NOT NULL,\n"
                    + " content BLOB NOT NULL,\n"
                    + " timestamp DATETIME DEFAULT CURRENT_TIMESTAMP\n"
                    + ");";
            stmt.execute(sql);
            logger.info("Database initialized.");
        } catch (Exception e) {
            logger.error("Database initialization failed", e);
        }
    }
}
