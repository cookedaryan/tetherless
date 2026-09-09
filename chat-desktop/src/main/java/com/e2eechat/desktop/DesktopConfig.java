package com.e2eechat.desktop;

import com.e2eechat.core.network.TlsSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Resolves where the client connects and which certificate it pins.
 *
 * <p>This used to be inline in {@code main}, which made it untestable and left the TLS truststore
 * reachable only through a JVM flag - fine for a developer running Gradle, useless for someone who
 * installed the app and has nowhere to put one.
 *
 * <h2>Precedence</h2>
 * Command-line arguments beat system properties, which beat {@code config.properties}, which beats
 * the settings sheet's stored preference, which beats the built-in defaults. System properties sit
 * above the file so a launcher script or an operator can override a deployed configuration without
 * editing it, and the toggle sits below the file so a deployment that mandates a setting - the
 * update check being the one that matters - cannot be overridden from inside the app.
 *
 * <p>Only the update check reads the preference rung; host, port and truststore have no in-app
 * control and stop at the file.
 */
public final class DesktopConfig {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopConfig.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8080;

    /** Where the settings toggle stores its answer. Mirrors how Theme stores dark mode. */
    private static final Preferences PREFS = Preferences.userRoot().node("com/e2eechat/desktop");

    private static final String PREF_UPDATES = "updateChecks";
    private static final String PREF_NOTIFICATIONS = "notifications";
    private static final String PREF_NOTIFICATION_PREVIEW = "notificationPreview";

    /** Key in {@code config.properties} for the relay's pinned certificate. */
    static final String TRUSTSTORE_KEY = "truststore";

    /** Key in {@code config.properties} for that truststore's password. */
    static final String TRUSTSTORE_PASSWORD_KEY = "truststore.password";

    /** Key in {@code config.properties} switching the startup update check off. */
    static final String UPDATES_KEY = "updates";

    /**
     * What a rung above the settings toggle decided about the update check, or {@code null} when
     * nothing above it has an opinion and the toggle is what decides.
     *
     * <p>Recorded by {@link #load(File, String[])}. The settings sheet needs it: it has to render
     * the value in force rather than the value stored, and it has to know when offering a switch
     * would be a lie because flipping it changes nothing.
     */
    private static volatile Boolean updateChecksOverride;

    /** Records the user's choice. Configuration set by a deployment still wins over this. */
    public static void setUpdateChecksPreference(boolean enabled) {
        PREFS.putBoolean(PREF_UPDATES, enabled);
    }

    /** The stored choice, defaulting to on. */
    public static boolean updateChecksPreference() {
        return PREFS.getBoolean(PREF_UPDATES, true);
    }

    /** Forgets the stored choice, so the built-in default applies again. Used by tests. */
    public static void clearUpdateChecksPreference() {
        PREFS.remove(PREF_UPDATES);
    }

    /**
     * Whether a rung above the settings toggle has already decided the update check, and which
     * way. {@code null} when the toggle is what decides.
     *
     * <p>Meaningful only once {@link #load(File, String[])} has run, which it has before any
     * window exists.
     */
    public static Boolean updateChecksOverride() {
        return updateChecksOverride;
    }

    /**
     * The update-check setting actually in force: whatever was pinned above the toggle, and
     * otherwise the stored choice.
     *
     * <p>This, not {@link #updateChecksPreference()}, is what a switch should show. Rendering the
     * stored choice meant a client with {@code updates=false} in its configuration displayed the
     * switch on while never checking for anything.
     */
    public static boolean effectiveUpdateChecks() {
        Boolean pinned = updateChecksOverride;
        return pinned == null ? updateChecksPreference() : pinned.booleanValue();
    }

    public static void setNotificationsPreference(boolean enabled) {
        PREFS.putBoolean(PREF_NOTIFICATIONS, enabled);
    }

    /** Whether an arriving message raises a desktop notification at all. Defaults to on. */
    public static boolean notificationsPreference() {
        return PREFS.getBoolean(PREF_NOTIFICATIONS, true);
    }

    public static void setNotificationPreviewPreference(boolean enabled) {
        PREFS.putBoolean(PREF_NOTIFICATION_PREVIEW, enabled);
    }

    /**
     * Whether a notification carries the message text as well as who sent it.
     *
     * <p>Defaults to <strong>off</strong>, and that is a deliberate asymmetry with the rest of the
     * app being helpful. A notification is handed to the operating system, which shows it on the
     * lock screen, keeps it in a notification centre, and on Windows may sync it to other devices.
     * Text that was end-to-end encrypted the whole way to this machine would be copied there in
     * the clear, and the person who chose this app is the least likely to want that by default.
     */
    public static boolean notificationPreviewPreference() {
        return PREFS.getBoolean(PREF_NOTIFICATION_PREVIEW, false);
    }

    /**
     * Remembers where the window was and how big it was.
     *
     * <p>Stored per user rather than in the profile directory: it describes this person's screen,
     * not this identity, and two profiles open side by side on one machine should not fight over
     * one remembered position.
     */
    public static void saveWindowBounds(int x, int y, int width, int height, boolean maximised) {
        PREFS.putInt("windowX", x);
        PREFS.putInt("windowY", y);
        PREFS.putInt("windowWidth", width);
        PREFS.putInt("windowHeight", height);
        PREFS.putBoolean("windowMaximised", maximised);
    }

    /** The remembered bounds, or {@code null} the first time or if they no longer fit a screen. */
    public static java.awt.Rectangle windowBounds() {
        int width = PREFS.getInt("windowWidth", -1);
        int height = PREFS.getInt("windowHeight", -1);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new java.awt.Rectangle(PREFS.getInt("windowX", 0), PREFS.getInt("windowY", 0),
                width, height);
    }

    public static boolean windowMaximised() {
        return PREFS.getBoolean("windowMaximised", false);
    }

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

        // Precedence, lowest last: system properties, config.properties, the settings toggle,
        // then the built-in default. The toggle sits below configuration deliberately - a
        // deployment that mandates updates=false must not be overridable from the settings sheet.
        // Which of those two answered is recorded as well as the answer, because the settings
        // sheet renders a switch and must not offer one that changes nothing.
        String pinned = firstNonEmpty(
                System.getProperty(UpdateChecker.ENABLED_PROPERTY),
                file.getProperty(UPDATES_KEY),
                null);
        updateChecksOverride = pinned == null
                ? null : Boolean.valueOf(!"false".equalsIgnoreCase(pinned));
        boolean updates = pinned == null
                ? updateChecksPreference() : !"false".equalsIgnoreCase(pinned);

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
