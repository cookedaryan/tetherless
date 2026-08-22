package com.e2eechat.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists the local user's display name across launches.
 *
 * <p>The peer id others use to reach you is {@code displayName@fingerprintPrefix}. The fingerprint
 * half comes from the identity key and is stable, but the name half was previously never stored:
 * it was collected on first run and then hardcoded to "Me" on every launch after, so a user's id
 * silently changed the second time they opened the app and contacts could no longer route to them.
 * Keeping the name here is what makes the id stable.
 *
 * <p>This is plain configuration, not secret material - the display name is broadcast in every
 * message header anyway - so it is stored unencrypted alongside the keystore.
 */
public class ProfileStore {

    private static final Logger logger = LoggerFactory.getLogger(ProfileStore.class);

    private static final String FILE_NAME = "profile.properties";
    private static final String KEY_DISPLAY_NAME = "display.name";

    /** Peer ids split on the first '@', so a name may not contain one. */
    private static final int MAX_NAME_LENGTH = 64;

    private final File file;

    public ProfileStore(File configDir) {
        this.file = new File(configDir, FILE_NAME);
    }

    /** The stored display name, or empty if this profile predates the name being persisted. */
    public Optional<String> getDisplayName() {
        if (!file.exists()) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            logger.warn("Could not read {}", file, e);
            return Optional.empty();
        }
        String name = props.getProperty(KEY_DISPLAY_NAME);
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(name.trim());
    }

    public void setDisplayName(String name) {
        String normalized = normalize(name);
        Properties props = new Properties();
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (Exception e) {
                logger.warn("Could not read {} before writing; rewriting it", file, e);
            }
        }
        props.setProperty(KEY_DISPLAY_NAME, normalized);
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "Tetherless profile. display.name forms the first half of your peer id.");
        } catch (Exception e) {
            logger.error("Could not persist display name to {}", file, e);
        }
    }

    /**
     * Makes a name safe to embed in a peer id: trimmed, length-capped, and free of the '@'
     * separator that {@code name@fingerprint} is split on.
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.trim().replace("@", "");
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
        }
        return cleaned;
    }

    /** True when {@code name} is usable as the first half of a peer id. */
    public static boolean isValid(String name) {
        return !normalize(name).isEmpty();
    }
}
