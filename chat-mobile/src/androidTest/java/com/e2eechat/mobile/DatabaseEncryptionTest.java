package com.e2eechat.mobile;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves the message database is genuinely encrypted on disk.
 *
 * <p>Configuring SQLCipher and assuming it took effect is exactly the sort of claim that turns out
 * to be false, so these tests read the raw file back and look for the plaintext. Message bodies used
 * to sit in the clear here, readable by anything with access to the app's data directory.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseEncryptionTest {

    private static final String DB_NAME = "encryption-test.db";

    private Context context;
    private AppDatabase database;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DB_NAME);
        database = openEncrypted();
    }

    @After
    public void tearDown() {
        if (database != null && database.isOpen()) {
            database.close();
        }
        context.deleteDatabase(DB_NAME);
    }

    private AppDatabase openEncrypted() throws Exception {
        byte[] key = DatabaseKeyStore.getOrCreate(context);
        return Room.databaseBuilder(context, AppDatabase.class, DB_NAME)
                .openHelperFactory(new SupportOpenHelperFactory(DatabaseKeyStore.asPassphrase(key)))
                .build();
    }

    /** The headline check: a stored message must not be findable in the file. */
    @Test
    public void messageTextIsNotReadableInTheDatabaseFile() throws Exception {
        String secret = "meet-me-at-the-observatory-" + UUID.randomUUID();
        database.messageDao().insert(message(secret));
        database.close();

        byte[] raw = readFile(context.getDatabasePath(DB_NAME));
        assertTrue("the database file should not be empty", raw.length > 0);
        assertFalse("message text was found in plaintext on disk",
                contains(raw, secret.getBytes(StandardCharsets.UTF_8)));
    }

    /** Peer ids are metadata a column-level scheme would leave exposed. */
    @Test
    public void peerIdsAreNotReadableInTheDatabaseFile() throws Exception {
        String peer = "fd40680148d245953ad5ce85a27f6235";
        MessageEntity entity = message("body");
        entity.conversationId = peer;
        entity.sender = peer;
        database.messageDao().insert(entity);
        database.close();

        byte[] raw = readFile(context.getDatabasePath(DB_NAME));
        assertFalse("a peer id was found in plaintext on disk",
                contains(raw, peer.getBytes(StandardCharsets.UTF_8)));
    }

    /** An encrypted SQLite file must not begin with the standard plaintext header. */
    @Test
    public void fileDoesNotCarryThePlainSqliteHeader() throws Exception {
        database.messageDao().insert(message("anything"));
        database.close();

        byte[] raw = readFile(context.getDatabasePath(DB_NAME));
        byte[] header = "SQLite format 3".getBytes(StandardCharsets.UTF_8);
        byte[] actual = new byte[header.length];
        System.arraycopy(raw, 0, actual, 0, header.length);

        assertFalse("the file starts with the unencrypted SQLite header",
                java.util.Arrays.equals(header, actual));
    }

    /** Encryption is worthless if the app cannot read its own data back. */
    @Test
    public void messagesRoundTripThroughEncryption() throws Exception {
        database.messageDao().insert(message("round trip"));
        database.close();

        database = openEncrypted();
        assertEquals(1, database.messageDao().getAllForExport().size());
        assertEquals("round trip", database.messageDao().getAllForExport().get(0).content);
    }

    /** Opening with the wrong key must fail rather than silently returning nothing. */
    @Test
    public void theWrongKeyCannotOpenTheDatabase() throws Exception {
        database.messageDao().insert(message("private"));
        database.close();

        byte[] wrongKey = new byte[32];
        java.util.Arrays.fill(wrongKey, (byte) 0x42);
        AppDatabase wrong = Room.databaseBuilder(context, AppDatabase.class, DB_NAME)
                .openHelperFactory(new SupportOpenHelperFactory(wrongKey))
                .build();
        try {
            wrong.messageDao().getAllForExport();
            fail("the database opened with the wrong key");
        } catch (Exception expected) {
            // SQLCipher reports a corrupt or unreadable file, which is the correct outcome.
        } finally {
            wrong.close();
        }
        database = openEncrypted();
    }

    // ------------------------------------------------------------------ key

    /** The key must be stable, or every launch would lose the previous history. */
    @Test
    public void theDatabaseKeyIsStableAcrossCalls() throws Exception {
        assertArrayEquals(DatabaseKeyStore.getOrCreate(context),
                DatabaseKeyStore.getOrCreate(context));
    }

    @Test
    public void theDatabaseKeyIs256Bits() throws Exception {
        assertEquals(32, DatabaseKeyStore.getOrCreate(context).length);
    }

    /** The stored blob is the wrapped key, so the raw key must not appear in preferences. */
    @Test
    public void theRawKeyIsNotStoredInPreferences() throws Exception {
        byte[] key = DatabaseKeyStore.getOrCreate(context);
        String wrapped = context.getSharedPreferences("tetherless-db-key", Context.MODE_PRIVATE)
                .getString("wrapped", null);
        assertNotNull(wrapped);

        byte[] blob = android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP);
        assertFalse("the unwrapped key is sitting in preferences", contains(blob, key));
    }

    // -------------------------------------------------------------- helpers

    private static MessageEntity message(String content) {
        MessageEntity entity = new MessageEntity();
        entity.messageId = UUID.randomUUID().toString();
        entity.conversationId = "peer";
        entity.sender = "peer";
        entity.receiver = "me";
        entity.content = content;
        entity.direction = "IN";
        entity.deliveryState = "DELIVERED";
        entity.sentAt = 1000L;
        entity.receivedAt = 1000L;
        return entity;
    }

    /** Reads the database plus its write-ahead log, since recent rows may live there. */
    private static byte[] readFile(File file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String suffix : new String[]{"", "-wal"}) {
            File f = new File(file.getPath() + suffix);
            if (!f.exists()) {
                continue;
            }
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
        }
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
