package com.e2eechat.desktop;

import com.e2eechat.core.network.TlsSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/**
 * Resolves where the client connects and which certificate it pins.
 *
 * <p>This used to be inline in {@code main}, which made it untestable and left the TLS truststore
 * reachable only through a JVM flag - fine for a developer running Gradle, useless for someone who
 * installed the app and has nowhere to put one.
 *
 * <h2>Precedence</h2>
 * Command-line arguments beat system properties, which beat {@code config.properties}, which beats
 * the built-in defaults. System properties sit above the file so a launcher script or an operator
 * can override a deployed configuration without editing it.
 */
public final class DesktopConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfig.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    /** Key in {@code config.properties} for the relay's pinned certificate. */
    static final String TRUSTSTORE_KEY = "truststore";

    /** Key in {@code config.properties} for that truststore's password. */
    static final String TRUSTSTORE_PASSWORD_KEY = "truststore.password";

    /** Key in {@code config.properties} switching the startup update check off. */
    static final String UPDATES_KEY = "updates";

    private final String host;
    private final int port;
    private final String trustStorePath;
    private final String trustStorePassword;
    private final boolean updateChecks;

    DesktopConfig(String host, int port, String trustStorePath, String trustStorePassword) {
        this(host, port, trustStorePath, trustStorePassword, true);
    }

    DesktopConfig(String host, int port, String trustStorePath, String trustStorePassword,
                  boolean updateChecks) {
        this.host = host;
        this.port = port;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.updateChecks = updateChecks;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    /** The configured truststore path, or {@code null} when none was set. */
    public String trustStorePath() {
        return trustStorePath;
    }

    /**
     * Whether the client may ask GitHub about newer releases at startup.
     *
     * <p>On by default, and worth being able to turn off: the request tells GitHub, and anyone
     * watching the network, that this address runs Tetherless and when it started. See
     * {@link UpdateChecker}.
     */
    public boolean updateChecks() {
        return updateChecks;
    }

    /**
     * Reads the configuration for this profile.
     *
     * @param configDir the profile directory, normally {@code ~/.tetherless}
     * @param args      the process arguments: optional host, then optional port
     */
    public static DesktopConfig load(File configDir, String[] args) {
        Properties file = readFile(new File(configDir, "config.properties"));

        String host = firstNonEmpty(
                System.getProperty("tetherless.host"),
                file.getProperty("host"),
                DEFAULT_HOST);

        int port = parsePort(firstNonEmpty(
                System.getProperty("tetherless.port"),
                file.getProperty("port"),
                String.valueOf(DEFAULT_PORT)));

        if (args != null && args.length > 0 && !args[0].isEmpty()) {
            host = args[0];
        }
        if (args != null && args.length > 1) {
            port = parsePort(args[1]);
        }

        // A truststore already on the command line wins; otherwise take the profile's.
        String trustStore = firstNonEmpty(
                System.getProperty(TlsSupport.TRUSTSTORE_PROPERTY),
                resolveAgainst(configDir, file.getProperty(TRUSTSTORE_KEY)),
                null);
        String trustStorePassword = firstNonEmpty(
                System.getProperty(TlsSupport.PASSWORD_PROPERTY),
                file.getProperty(TRUSTSTORE_PASSWORD_KEY),
                null);

        boolean updates = !"false".equalsIgnoreCase(firstNonEmpty(
                System.getProperty(UpdateChecker.ENABLED_PROPERTY),
                file.getProperty(UPDATES_KEY),
                "true"));

        return new DesktopConfig(host, port, trustStore, trustStorePassword, updates);
    }

    /**
     * Publishes the TLS settings where {@link TlsSupport} looks for them.
     *
     * <p>Called before the first connection. When nothing is configured this does nothing, leaving
     * {@code TlsSupport} to decide - which for a packaged build means refusing to connect rather
     * than trusting the development certificate.
     */
    public void applyTlsProperties() {
        if (trustStorePath != null) {
            System.setProperty(TlsSupport.TRUSTSTORE_PROPERTY, trustStorePath);
            LOG.info("Pinning the relay certificate from {}", trustStorePath);
        }
        if (trustStorePassword != null) {
            System.setProperty(TlsSupport.PASSWORD_PROPERTY, trustStorePassword);
        }
        if (!updateChecks) {
            System.setProperty(UpdateChecker.ENABLED_PROPERTY, "false");
            LOG.info("Update checks are switched off for this profile");
        }
    }

    /** A relative truststore path is taken as relative to the profile directory. */
    private static String resolveAgainst(File configDir, String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path.trim());
        return file.isAbsolute() ? file.getPath() : new File(configDir, path.trim()).getPath();
    }

    private static Properties readFile(File configFile) {
        Properties properties = new Properties();
        if (!configFile.isFile()) {
            return properties;
        }
        try (FileInputStream in = new FileInputStream(configFile)) {
            properties.load(in);
        } catch (Exception e) {
            // A malformed config file should not stop the app starting; the defaults still work.
            LOG.warn("Could not read {}: {}", configFile, e.getMessage());
        }
        return properties;
    }

    private static int parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed > 0 && parsed <= 65535) {
                return parsed;
            }
            LOG.warn("Port {} is out of range; using {}", parsed, DEFAULT_PORT);
        } catch (NumberFormatException e) {
            LOG.warn("Port '{}' is not a number; using {}", value, DEFAULT_PORT);
        }
        return DEFAULT_PORT;
    }

    private static String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return fallback;
    }
}
