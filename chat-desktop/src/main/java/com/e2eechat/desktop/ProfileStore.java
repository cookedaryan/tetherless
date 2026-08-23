package com.e2eechat.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

/**
 * Persists the local user's display name across launches.
 *
 * <p>The name is a label only: peer ids are derived from the identity key, so renaming yourself no
 * longer changes the address anyone routes to. It still has to persist, because it was previously
 * collected on first run and then hardcoded to "Me" on every launch after, which made a peer's
 * label change under them each time the app opened.
 *
 * <p>This is plain configuration, not secret material - the name is sent to peers in every HELLO
 * anyway - so it is stored unencrypted alongside the keystore.
 */
public class ProfileStore {

    private static final Logger logger = LoggerFactory.getLogger(ProfileStore.class);

    private static final String FILE_NAME = "profile.properties";
    private static final String KEY_DISPLAY_NAME = "display.name";

    /**
     * Parameters the database key was derived with. Stored rather than assumed so the iteration
     * count can be raised for new profiles without making existing databases unreadable: each
     * profile keeps deriving with the values it was created under until it is migrated.
     */
    private static final String KEY_KDF_SALT = "kdf.salt";
    private static final String KEY_KDF_ITERATIONS = "kdf.iterations";

    /** Peer ids split on the first '@', so a name may not contain one. */
    private static final int MAX_NAME_LENGTH = 64;

    private final File file;

    public ProfileStore(File configDir) {
        this.file = new File(configDir, FILE_NAME);
    }

    /** Loads the profile, or an empty set of properties if it does not exist yet. */
    private Properties read() {
        Properties props = new Properties();
        if (!file.exists()) {
            return props;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Exception e) {
            logger.warn("Could not read {}", file, e);
        }
        return props;
    }

    /** Writes the whole profile back, so one setter never drops another's keys. */
    private void write(Properties props) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            props.store(out, "Tetherless profile. display.name is the label peers see; "
                    + "your peer id comes from your identity key and is unaffected by it. "
                    + "kdf.* are the parameters the local database key is derived with.");
        } catch (Exception e) {
            logger.error("Could not write {}", file, e);
        }
    }

    /** The stored display name, or empty if this profile predates the name being persisted. */
    public Optional<String> getDisplayName() {
        String name = read().getProperty(KEY_DISPLAY_NAME);
        if (name == null || name.trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(name.trim());
    }

    public void setDisplayName(String name) {
        Properties props = read();
        props.setProperty(KEY_DISPLAY_NAME, normalize(name));
        write(props);
    }

    /**
     * The stored key-derivation parameters, or empty for a profile written before the database key
     * was derived with PBKDF2 - which is the signal that it needs migrating.
     */
    public Optional<KdfParameters> getKdfParameters() {
        Properties props = read();
        String salt = props.getProperty(KEY_KDF_SALT);
        String iterations = props.getProperty(KEY_KDF_ITERATIONS);
        if (salt == null || iterations == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new KdfParameters(
                    Base64.getDecoder().decode(salt), Integer.parseInt(iterations)));
        } catch (Exception e) {
            logger.warn("Unreadable KDF parameters in {}; treating the profile as unmigrated", file);
            return Optional.empty();
        }
    }

    public void setKdfParameters(byte[] salt, int iterations) {
        Properties props = read();
        props.setProperty(KEY_KDF_SALT, Base64.getEncoder().encodeToString(salt));
        props.setProperty(KEY_KDF_ITERATIONS, Integer.toString(iterations));
        write(props);
    }

    /** Salt and iteration count a profile's database key was derived with. */
    public static final class KdfParameters {
        public final byte[] salt;
        public final int iterations;

        KdfParameters(byte[] salt, int iterations) {
            this.salt = salt;
            this.iterations = iterations;
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
