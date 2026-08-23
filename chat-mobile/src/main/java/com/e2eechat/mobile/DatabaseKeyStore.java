package com.e2eechat.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * Holds the key that encrypts the local message database.
 *
 * <h2>Why the key is wrapped rather than stored</h2>
 * SQLCipher needs the raw key bytes in memory to open the database, so the key itself cannot live
 * inside the Android keystore. Instead a random 256-bit database key is generated once and
 * <em>wrapped</em> with an AES key that is generated in {@code AndroidKeyStore} and never leaves it.
 * Only the wrapped blob is written to preferences.
 *
 * <p>The consequence is that the database is bound to this device and this install. Copying the
 * files to another device, or extracting them from a backup, yields nothing usable, because the
 * unwrapping key is held by the platform - in a secure element where the hardware supports it - and
 * cannot be exported. It also means an app uninstall destroys the history permanently.
 *
 * <p>This is stronger than the desktop's passphrase-derived key, which is only as good as the
 * passphrase. It is weaker in one respect: there is nothing to type, so an attacker who can run code
 * as this app on an unlocked device can ask the keystore to unwrap for them. It protects the files
 * at rest, not a live compromised process.
 */
public final class DatabaseKeyStore {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String WRAPPING_ALIAS = "tetherless-db-wrapping-key";
    private static final String PREFS = "tetherless-db-key";
    private static final String KEY_WRAPPED = "wrapped";
    private static final String KEY_IV = "iv";
    private static final String KEY_ENCRYPTED = "database.encrypted";

    private static final int DB_KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;

    private DatabaseKeyStore() {
    }

    /**
     * Returns the database key, generating and wrapping one on first use.
     *
     * @return 32 raw key bytes; the caller should not persist them anywhere
     */
    public static byte[] getOrCreate(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);

        String wrapped = prefs.getString(KEY_WRAPPED, null);
        String iv = prefs.getString(KEY_IV, null);
        if (wrapped != null && iv != null) {
            return unwrap(Base64.decode(wrapped, Base64.NO_WRAP),
                    Base64.decode(iv, Base64.NO_WRAP));
        }

        byte[] databaseKey = new byte[DB_KEY_BYTES];
        new SecureRandom().nextBytes(databaseKey);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
        byte[] blob = cipher.doFinal(databaseKey);

        prefs.edit()
                .putString(KEY_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();

        return databaseKey;
    }

    /**
     * True once the database file itself is known to be encrypted.
     *
     * <p>Deliberately separate from whether a wrapped key exists: the key is created as a side
     * effect of opening the database, so a migration that failed part-way would leave a key behind
     * and, were the key the marker, the plaintext file would be skipped forever and its history
     * stranded. This flag is set only once the encrypted database is actually in use.
     */
    public static boolean isDatabaseEncrypted(Context context) {
        return prefs(context).getBoolean(KEY_ENCRYPTED, false);
    }

    public static void markDatabaseEncrypted(Context context) {
        prefs(context).edit().putBoolean(KEY_ENCRYPTED, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static byte[] unwrap(byte[] blob, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(blob);
    }

    /** The AndroidKeyStore AES key that wraps the database key. Created once per install. */
    private static SecretKey wrappingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(WRAPPING_ALIAS)) {
            SecretKey existing = (SecretKey) keyStore.getKey(WRAPPING_ALIAS, null);
            if (existing != null) {
                return existing;
            }
            keyStore.deleteEntry(WRAPPING_ALIAS);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(WRAPPING_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Not requiring user authentication: the foreground service has to open the
                // database while the screen is locked, or messages stop being stored.
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    /** The raw key bytes SQLCipher opens the database with. Copied so the caller can wipe ours. */
    static byte[] asPassphrase(byte[] rawKey) {
        return rawKey.clone();
    }

    static void wipe(byte[] key) {
        java.util.Arrays.fill(key, (byte) 0);
    }
}
