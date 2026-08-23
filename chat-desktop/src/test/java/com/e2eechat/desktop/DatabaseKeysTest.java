package com.e2eechat.desktop;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.SecretKey;
import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Covers the at-rest key derivation and the one-time migration off the old one.
 *
 * <p>The migration is the risky part: get it wrong and a user's entire message history becomes
 * permanently unreadable, which is worse than the weak derivation it replaces.
 */
public class DatabaseKeysTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File configDir;
    private String dbPath;

    @Before
    public void setUp() throws Exception {
        configDir = tmp.newFolder("profile");
        dbPath = new File(configDir, "chat.db").getAbsolutePath();
        DatabaseHelper.initializeDatabase(dbPath);
    }

    // -------------------------------------------------------------- derivation

    @Test
    public void derivationIsDeterministic() throws Exception {
        byte[] salt = DatabaseKeys.newSalt();
        assertEquals(
                encoded(DatabaseKeys.derive("hunter2".toCharArray(), salt, 1000)),
                encoded(DatabaseKeys.derive("hunter2".toCharArray(), salt, 1000)));
    }

    @Test
    public void differentSaltsGiveDifferentKeys() throws Exception {
        assertNotEquals(
                encoded(DatabaseKeys.derive("hunter2".toCharArray(), DatabaseKeys.newSalt(), 1000)),
                encoded(DatabaseKeys.derive("hunter2".toCharArray(), DatabaseKeys.newSalt(), 1000)));
    }

    @Test
    public void differentPassphrasesGiveDifferentKeys() throws Exception {
        byte[] salt = DatabaseKeys.newSalt();
        assertNotEquals(
                encoded(DatabaseKeys.derive("hunter2".toCharArray(), salt, 1000)),
                encoded(DatabaseKeys.derive("hunter3".toCharArray(), salt, 1000)));
    }

    @Test
    public void derivedKeyIs256Bits() throws Exception {
        assertEquals(32,
                DatabaseKeys.derive("pw".toCharArray(), DatabaseKeys.newSalt(), 1000)
                        .getEncoded().length);
    }

    /** Salts must not repeat, or two profiles with one passphrase share a key. */
    @Test
    public void saltsAreRandom() {
        assertNotEquals(encodedBytes(DatabaseKeys.newSalt()), encodedBytes(DatabaseKeys.newSalt()));
        assertEquals(16, DatabaseKeys.newSalt().length);
    }

    /**
     * The iteration count is the entire defence against offline guessing. This guards the constant
     * against being quietly lowered - OWASP's floor for PBKDF2-HMAC-SHA256 is 210,000.
     */
    @Test
    public void iterationCountMeetsTheCurrentFloor() {
        assertTrue("PBKDF2 iterations must not drop below 210,000",
                DatabaseKeys.ITERATIONS >= 210_000);
    }

    /** A high iteration count is worthless if it is slow enough to be skipped in practice. */
    @Test
    public void derivationAtFullStrengthIsFastEnoughToUse() throws Exception {
        long start = System.currentTimeMillis();
        DatabaseKeys.derive("a passphrase".toCharArray(),
                DatabaseKeys.newSalt(), DatabaseKeys.ITERATIONS);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("derivation took " + elapsed + "ms, too slow for an unlock prompt",
                elapsed < 5000);
    }

    // --------------------------------------------------------------- migration

    /** The point of the migration: history written under the old key still reads afterwards. */
    @Test
    public void migrationPreservesHistory() throws Exception {
        char[] passphrase = "correct horse".toCharArray();
        String clientId = "fd40680148d245953ad5ce85a27f6235";

        SecretKey legacy = DatabaseKeys.legacyHkdf(passphrase, clientId);
        MessageRepository old = new MessageRepository(dbPath, legacy);
        old.saveMessage(UUID.randomUUID().toString(), "alice", "bob", "first message",
                1000L, ChatMessage.Status.SENT, null, null, null, true);
        old.saveMessage(UUID.randomUUID().toString(), "bob", "alice", "second message",
                2000L, ChatMessage.Status.DELIVERED, "mid", "alice", "quoted text", false);

        byte[] salt = DatabaseKeys.newSalt();
        SecretKey modern = DatabaseKeys.derive(passphrase, salt, 1000);
        assertEquals(2, old.migrateEncryption(legacy, modern));

        MessageRepository migrated = new MessageRepository(dbPath, modern);
        List<ChatMessage> messages = migrated.getMessages("alice", "bob", 10);
        assertEquals(2, messages.size());
        assertEquals("first message", messages.get(0).getContent());
        assertEquals("second message", messages.get(1).getContent());
        assertEquals("the encrypted reply preview must migrate too",
                "quoted text", messages.get(1).getReplyToPreview());
    }

    /** After migrating, the old key must no longer read anything. */
    @Test
    public void theOldKeyStopsWorkingAfterMigration() throws Exception {
        char[] passphrase = "correct horse".toCharArray();
        SecretKey legacy = DatabaseKeys.legacyHkdf(passphrase, "someclientid");
        MessageRepository old = new MessageRepository(dbPath, legacy);
        old.saveMessage(UUID.randomUUID().toString(), "alice", "bob", "secret",
                1000L, ChatMessage.Status.SENT, null, null, null, true);

        SecretKey modern = DatabaseKeys.derive(passphrase, DatabaseKeys.newSalt(), 1000);
        old.migrateEncryption(legacy, modern);

        List<ChatMessage> underOldKey = old.getMessages("alice", "bob", 10);
        assertEquals(1, underOldKey.size());
        assertNotEquals("the old key must not still decrypt the row",
                "secret", underOldKey.get(0).getContent());
    }

    /** An empty database migrates cleanly rather than erroring. */
    @Test
    public void migratingAnEmptyDatabaseSucceeds() throws Exception {
        SecretKey a = DatabaseKeys.derive("x".toCharArray(), DatabaseKeys.newSalt(), 1000);
        SecretKey b = DatabaseKeys.derive("y".toCharArray(), DatabaseKeys.newSalt(), 1000);
        assertEquals(0, new MessageRepository(dbPath, a).migrateEncryption(a, b));
    }

    /**
     * If a row cannot be decrypted with the old key, the whole migration must roll back. Committing
     * a partial re-encryption would leave some rows under each key, which is unrecoverable.
     */
    @Test
    public void migrationRollsBackWhenARowCannotBeRead() throws Exception {
        char[] passphrase = "correct horse".toCharArray();
        SecretKey legacy = DatabaseKeys.legacyHkdf(passphrase, "someclientid");
        MessageRepository repo = new MessageRepository(dbPath, legacy);
        repo.saveMessage(UUID.randomUUID().toString(), "alice", "bob", "readable",
                1000L, ChatMessage.Status.SENT, null, null, null, true);

        SecretKey wrongKey = DatabaseKeys.derive("not the passphrase".toCharArray(),
                DatabaseKeys.newSalt(), 1000);
        SecretKey target = DatabaseKeys.derive(passphrase, DatabaseKeys.newSalt(), 1000);

        assertEquals("a failed migration must report failure", -1,
                repo.migrateEncryption(wrongKey, target));

        // The original key must still work, proving nothing was committed.
        assertEquals("readable", repo.getMessages("alice", "bob", 10).get(0).getContent());
    }

    // ------------------------------------------------------------ profile store

    @Test
    public void kdfParametersRoundTrip() {
        ProfileStore store = new ProfileStore(configDir);
        assertFalse("a fresh profile has no KDF parameters",
                store.getKdfParameters().isPresent());

        byte[] salt = DatabaseKeys.newSalt();
        store.setKdfParameters(salt, DatabaseKeys.ITERATIONS);

        ProfileStore reopened = new ProfileStore(configDir);
        assertTrue(reopened.getKdfParameters().isPresent());
        assertEquals(encodedBytes(salt), encodedBytes(reopened.getKdfParameters().get().salt));
        assertEquals(DatabaseKeys.ITERATIONS, reopened.getKdfParameters().get().iterations);
    }

    /** Writing KDF parameters must not drop the display name, or the peer id label resets. */
    @Test
    public void settersDoNotClobberEachOther() {
        ProfileStore store = new ProfileStore(configDir);
        store.setDisplayName("Aryan");
        store.setKdfParameters(DatabaseKeys.newSalt(), DatabaseKeys.ITERATIONS);

        ProfileStore reopened = new ProfileStore(configDir);
        assertEquals("Aryan", reopened.getDisplayName().get());
        assertTrue(reopened.getKdfParameters().isPresent());
    }

    private static String encoded(SecretKey key) {
        return encodedBytes(key.getEncoded());
    }

    private static String encodedBytes(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
}
