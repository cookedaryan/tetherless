package com.e2eechat.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ServerConfig {
    private static final Logger logger = LoggerFactory.getLogger(ServerConfig.class);
    
    private int port = 8080;
    private int maxConnections = 500;
    private int maxConnectionsPerIp = 10;
    private int rateLimitBurst = 50;
    private int rateLimitRefillSec = 20;
    private int handshakeTimeoutMs = 10000;
    
    // TLS
    private String keystorePath = "dev-keystore.p12";
    private String keystorePassword = "changeit";

    public ServerConfig() {
        loadProperties();
    }

    private void loadProperties() {
        Properties props = new Properties();
        File configFile = new File("server.properties");
        
        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            } catch (IOException e) {
                logger.warn("Failed to load server.properties, using defaults", e);
            }
        }

        port = getInt(props, "server.port", "PORT", port);
        maxConnections = getInt(props, "server.max_connections", "MAX_CONNECTIONS", maxConnections);
        maxConnectionsPerIp = getInt(props, "server.max_connections_per_ip", "MAX_CONNECTIONS_PER_IP", maxConnectionsPerIp);
        rateLimitBurst = getInt(props, "server.rate_limit_burst", "RATE_LIMIT_BURST", rateLimitBurst);
        rateLimitRefillSec = getInt(props, "server.rate_limit_refill_sec", "RATE_LIMIT_REFILL_SEC", rateLimitRefillSec);
        handshakeTimeoutMs = getInt(props, "server.handshake_timeout_ms", "HANDSHAKE_TIMEOUT_MS", handshakeTimeoutMs);
        
        keystorePath = getString(props, "server.keystore_path", "KEYSTORE_PATH", keystorePath);
        keystorePassword = getString(props, "server.keystore_password", "KEYSTORE_PASSWORD", keystorePassword);
    }

    private int getInt(Properties props, String propKey, String envKey, int defaultValue) {
        // 1. Check environment variable
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) {
            try { return Integer.parseInt(envVal); } catch (NumberFormatException ignored) {}
        }
        // 2. Check system property (JVM arg)
        String sysVal = System.getProperty(propKey);
        if (sysVal != null && !sysVal.isEmpty()) {
            try { return Integer.parseInt(sysVal); } catch (NumberFormatException ignored) {}
        }
        // 3. Check properties file
        String propVal = props.getProperty(propKey);
        if (propVal != null && !propVal.isEmpty()) {
            try { return Integer.parseInt(propVal); } catch (NumberFormatException ignored) {}
        }
        // 4. Default
        return defaultValue;
    }

    private String getString(Properties props, String propKey, String envKey, String defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) return envVal;
        
        String sysVal = System.getProperty(propKey);
        if (sysVal != null && !sysVal.isEmpty()) return sysVal;
        
        String propVal = props.getProperty(propKey);
        if (propVal != null && !propVal.isEmpty()) return propVal;
        
        return defaultValue;
    }

    public int getPort() { return port; }
    public int getMaxConnections() { return maxConnections; }
    public int getMaxConnectionsPerIp() { return maxConnectionsPerIp; }
    public int getRateLimitBurst() { return rateLimitBurst; }
    public int getRateLimitRefillSec() { return rateLimitRefillSec; }
    public int getHandshakeTimeoutMs() { return handshakeTimeoutMs; }

    // Setters for tests
    public void setPort(int port) { this.port = port; }
    public void setMaxConnections(int max) { this.maxConnections = max; }
    public void setMaxConnectionsPerIp(int max) { this.maxConnectionsPerIp = max; }
    public void setRateLimitBurst(int burst) { this.rateLimitBurst = burst; }
    public void setRateLimitRefillSec(int refill) { this.rateLimitRefillSec = refill; }
    public void setHandshakeTimeoutMs(int ms) { this.handshakeTimeoutMs = ms; }
    
    public String getKeystorePath() { return keystorePath; }
    public void setKeystorePath(String path) { this.keystorePath = path; }
    
    public String getKeystorePassword() { return keystorePassword; }
    public void setKeystorePassword(String password) { this.keystorePassword = password; }
}
