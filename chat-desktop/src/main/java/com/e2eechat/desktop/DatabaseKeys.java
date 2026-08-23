package com.e2eechat.desktop;

import com.e2eechat.core.crypto.DHUtils;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Derives the key that encrypts message bodies at rest.
 *
 * <h2>Why PBKDF2 and not HKDF</h2>
 * This key comes from a passphrase a person typed, which has far less entropy than the key material
 * a KDF normally expands. HKDF is a single fast pass with no work factor: correct for stretching a
 * Diffie-Hellman output, wrong here, because it lets an attacker holding the database file guess
 * passphrases at the speed of a hash. PBKDF2 with a high iteration count makes each guess cost real
 * time. That is the whole defence - the file is on the attacker's disk, so nothing else slows them.
 *
 * <p>{@link #legacyHkdf} reproduces the old derivation, used once to re-encrypt an existing
 * database and then never again.
 */
public final class DatabaseKeys {

    /**
     * OWASP's floor for PBKDF2-HMAC-SHA256 at the time of writing. Raising it later is safe: the
     * count is stored per profile, so an existing database keeps deriving with the value it was
     * created under until it is migrated again.
     */
    public static final int ITERATIONS = 210_000;

    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private DatabaseKeys() {
    }

    /**
     * Shared because {@link SecureRandom} is thread-safe and seeding a fresh instance per call is
     * both wasteful and, on some platforms, a source of blocking on the entropy pool.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A fresh random salt, stored alongside the profile so the key can be derived again. */
    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * Derives the database key from a passphrase.
     *
     * <p>Takes {@code char[]} rather than {@code String} so the caller can zero it afterwards; a
     * String would linger in the heap until garbage collected and could not be cleared.
     */
    public static SecretKey derive(char[] passphrase, byte[] salt, int iterations)
            throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            // Clears the copy PBEKeySpec made of the passphrase.
            spec.clearPassword();
        }
    }

    /**
     * The pre-migration derivation: HKDF-SHA256 over the passphrase, salted with the client id.
     *
     * <p>Kept solely so an existing database can be read once and re-encrypted under
     * {@link #derive}. Never use it for a new profile.
     */
    public static SecretKey legacyHkdf(char[] passphrase, String clientId) throws Exception {
        byte[] ikm = new String(passphrase).getBytes(StandardCharsets.UTF_8);
        byte[] salt = clientId.getBytes(StandardCharsets.UTF_8);
        byte[] info = "tetherless-db-key".getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(DHUtils.hkdfSha256(ikm, salt, info, 32), "AES");
    }
}
