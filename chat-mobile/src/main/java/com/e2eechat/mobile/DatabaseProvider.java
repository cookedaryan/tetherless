package com.e2eechat.mobile;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Opens the message database, encrypting it, and migrating an existing plaintext one once.
 *
 * <p>The database used to be a plain Room file: message bodies sat on disk in the clear, readable
 * by anything with access to the app's data directory. It is now SQLCipher-backed, so the whole file
 * is encrypted - not only the message text but the participants, timestamps and counts that
 * column-level encryption would leave exposed.
 */
public final class DatabaseProvider {

    private static final String TAG = "DatabaseProvider";
    static final String DB_NAME = "chat.db";

    private DatabaseProvider() {
    }

    public static AppDatabase getDatabase(Context context) {
        Context app = context.getApplicationContext();

        // Gated on whether the file is known to be encrypted, not on whether a key exists: opening
        // the database creates a key as a side effect, so keying off that would skip the migration
        // forever after a single failed attempt and strand the old history.
        if (!DatabaseKeyStore.isDatabaseEncrypted(app)) {
            if (app.getDatabasePath(DB_NAME).exists()) {
                migratePlaintextDatabase(app);
            }
            DatabaseKeyStore.markDatabaseEncrypted(app);
        }
        return AppDatabase.getInstance(app);
    }

    /**
     * Copies a pre-encryption database into an encrypted one.
     *
     * <p>Reads the plaintext rows first, then replaces the file, then writes them back through the
     * encrypted handle. The plaintext file is only deleted once its contents are in memory, so an
     * interruption costs at worst an unencrypted leftover rather than the history itself.
     */
    private static synchronized void migratePlaintextDatabase(Context app) {
        // Room refuses database work on the main thread, and this can be reached from there. Doing
        // it on a worker and waiting is deliberate: letting the caller proceed would let the
        // encrypted database be created first, after which the key exists, the migration never
        // runs again, and the old history is stranded behind a file nothing opens.
        Thread worker = new Thread(() -> {
            try {
                java.util.List<MessageEntity> carried = AppDatabase.readPlaintext(app);
                Log.i(TAG, "migrating " + carried.size() + " messages into an encrypted database");

                File file = app.getDatabasePath(DB_NAME);
                AppDatabase.closePlaintext();
                deleteDatabaseFiles(file);

                AppDatabase encrypted = AppDatabase.getInstance(app);
                for (MessageEntity entity : carried) {
                    entity.id = 0;   // let the encrypted database assign its own row ids
                    encrypted.messageDao().insert(entity);
                }
                Log.i(TAG, "migration complete");
            } catch (Exception e) {
                // Leave the plaintext file alone: losing history would be worse than a delay in
                // encrypting it, and the next launch will try again.
                Log.e(TAG, "could not migrate the plaintext database; leaving it untouched", e);
            }
        }, "db-migration");

        worker.start();
        try {
            // Bounded: a local message table, read and rewritten once.
            worker.join(30_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** SQLite keeps -wal and -shm sidecars that must go with the main file. */
    private static void deleteDatabaseFiles(File file) {
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File f = new File(file.getPath() + suffix);
            if (f.exists() && !f.delete()) {
                Log.w(TAG, "could not delete " + f);
            }
        }
    }
}
