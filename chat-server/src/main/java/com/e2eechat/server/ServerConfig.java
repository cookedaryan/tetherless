package com.e2eechat.server;

import com.e2eechat.core.build.BuildInfo;

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

    /**
     * Interface the metrics endpoint listens on.
     *
     * <p>Loopback, because {@code /metrics} is unauthenticated plain HTTP. It used to bind the
     * wildcard address, so a relay on a public host with no firewall in front of it published its
     * client count, routed-message totals and rejection counters to anyone who asked - a live read
     * on who is using the service and how much, from the one component that is meant to learn as
     * little as possible. An operator who wants it reachable can say so; nobody should get that by
     * default for forgetting to close a port.
     */
    private String metricsHost = "127.0.0.1";
    
    // TLS
    static final String DEFAULT_KEYSTORE_PATH = "dev-keystore.p12";
    static final String DEFAULT_KEYSTORE_PASSWORD = "changeit";

    private String keystorePath = DEFAULT_KEYSTORE_PATH;
    private String keystorePassword = DEFAULT_KEYSTORE_PASSWORD;

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
        metricsHost = getString(props, "server.metrics_host", "METRICS_HOST", metricsHost);
        
        String configuredPath = getString(props, "server.keystore_path", "KEYSTORE_PATH", null);
        if (configuredPath != null) {
            keystorePath = configuredPath;
            keystoreConfigured = true;
        }
        String passwordFile = getString(props, "server.keystore_password_file",
                "KEYSTORE_PASSWORD_FILE", null);
        if (passwordFile != null) {
            keystorePassword = readPasswordFile(passwordFile);
        } else {
            keystorePassword = getString(props, "server.keystore_password", "KEYSTORE_PASSWORD",
                    keystorePassword);
        }
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
        if (envVal != null && !envVal.isEmpty()) {
            return envVal;
        }
        
        String sysVal = System.getProperty(propKey);
        if (sysVal != null && !sysVal.isEmpty()) {
            return sysVal;
        }
        
        String propVal = props.getProperty(propKey);
        if (propVal != null && !propVal.isEmpty()) {
            return propVal;
        }
        
        return defaultValue;
    }

    public int getPort() { return port; }
    public int getMaxConnections() { return maxConnections; }
    public int getMaxConnectionsPerIp() { return maxConnectionsPerIp; }
    public int getRateLimitBurst() { return rateLimitBurst; }
    public int getRateLimitRefillSec() { return rateLimitRefillSec; }
    public int getHandshakeTimeoutMs() { return handshakeTimeoutMs; }
    public String getMetricsHost() { return metricsHost; }

    // Setters for tests
    public void setPort(int port) { this.port = port; }
    public void setMetricsHost(String metricsHost) { this.metricsHost = metricsHost; }
    public void setMaxConnections(int max) { this.maxConnections = max; }
    public void setMaxConnectionsPerIp(int max) { this.maxConnectionsPerIp = max; }
    public void setRateLimitBurst(int burst) { this.rateLimitBurst = burst; }
    public void setRateLimitRefillSec(int refill) { this.rateLimitRefillSec = refill; }
    public void setHandshakeTimeoutMs(int ms) { this.handshakeTimeoutMs = ms; }
    
    public String getKeystorePath() { return keystorePath; }
    public void setKeystorePath(String path) {
        this.keystorePath = path;
        this.keystoreConfigured = true;
    }
    
    public String getKeystorePassword() { return keystorePassword; }
    public void setKeystorePassword(String password) { this.keystorePassword = password; }

    /**
     * Whether a keystore was actually configured, as opposed to falling through to the development
     * default. Setters count as configuration, so tests are unaffected.
     */
    private boolean keystoreConfigured;

    /**
     * Refuses to serve TLS from the development keystore in a packaged build.
     *
     * <p>The development keystore is generated by {@code scripts/generate-dev-cert.sh} and holds a
     * private key anyone can reproduce. A relay serving it would be trivially impersonated, and
     * because it is also the default, an operator who simply started the jar would get exactly that
     * with no indication anything was wrong. {@link BuildInfo} distinguishes a packaged build from a
     * development one; anything it cannot positively identify reads as development, so a damaged
     * stamp can never wave a release through.
     *
     * @throws IllegalStateException in a release build with no keystore configured
     */
    public void verifyTlsConfiguration() {
        if (BuildInfo.isRelease() && !keystoreConfigured) {
            throw new IllegalStateException(
                    "No TLS keystore configured. A packaged relay will not serve the development "
                            + "keystore, whose private key is reproducible by anyone. Set "
                            + "KEYSTORE_PATH and KEYSTORE_PASSWORD, or server.keystore_path and "
                            + "server.keystore_password in server.properties.");
        }
        if (keystoreConfigured && DEFAULT_KEYSTORE_PASSWORD.equals(keystorePassword)) {
            // Not fatal - it may genuinely be the password on their file - but this one guards a
            // private key, unlike the client's truststore password.
            logger.warn("The TLS keystore is using the default password. Unlike the client-side "
                    + "truststore password, this one protects a private key.");
        }
    }

    /**
     * Reads the keystore password from a file when {@code KEYSTORE_PASSWORD_FILE} points at one.
     *
     * <p>This is the convention Docker and Kubernetes secrets use, and it exists because the
     * alternative is worse: a password in an environment variable is visible to anyone who can run
     * {@code docker inspect}, and is inherited by every child process. This password protects the
     * relay's private key, so it is worth the file read.
     */
    private String readPasswordFile(String path) {
        File file = new File(path);
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(file.toPath());
            // A trailing newline is almost guaranteed if the file was written by a shell.
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read KEYSTORE_PASSWORD_FILE at "
                    + file.getAbsolutePath(), e);
        }
    }
}
