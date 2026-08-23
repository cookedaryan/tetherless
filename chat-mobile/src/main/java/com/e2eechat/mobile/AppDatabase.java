package com.e2eechat.mobile;

import android.content.Context;
import android.util.Log;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The local message store.
 *
 * <p>Backed by SQLCipher, so the database file is encrypted in full: message text, participants,
 * timestamps and counts alike. The key comes from {@link DatabaseKeyStore}, wrapped by a
 * non-exportable Android keystore key, so the file is useless if lifted off the device.
 */
@Database(entities = {MessageEntity.class}, version = 2)
public abstract class AppDatabase extends RoomDatabase {

    private static final String TAG = "AppDatabase";

    public abstract MessageDao messageDao();

    private static volatile AppDatabase instance;

    /** Only used to read a pre-encryption database once, during migration. */
    private static volatile AppDatabase plaintextInstance;

    static {
        // SQLCipher's native library has to be loaded before any database is opened.
        System.loadLibrary("sqlcipher");
    }

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `messages_new` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` TEXT, "
                    + "`conversationId` TEXT, `sender` TEXT, `receiver` TEXT, `content` TEXT, "
                    + "`sentAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, "
                    + "`direction` TEXT, `deliveryState` TEXT)");
            // The content column changed from byte[] to String, so old rows cannot be carried over.
            database.execSQL("DROP TABLE messages");
            database.execSQL("ALTER TABLE messages_new RENAME TO messages");
            database.execSQL("CREATE UNIQUE INDEX `index_messages_messageId` "
                    + "ON `messages` (`messageId`)");
        }
    };

    /** The encrypted database. */
    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = build(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private static AppDatabase build(Context app) {
        byte[] key = null;
        try {
            key = DatabaseKeyStore.getOrCreate(app);
            SupportOpenHelperFactory factory =
                    new SupportOpenHelperFactory(DatabaseKeyStore.asPassphrase(key));
            return Room.databaseBuilder(app, AppDatabase.class, DatabaseProvider.DB_NAME)
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_1_2)
                    .build();
        } catch (Exception e) {
            // Failing closed matters here: falling back to an unencrypted database would silently
            // undo the protection the user is entitled to assume is present.
            throw new IllegalStateException("Could not open the encrypted message database", e);
        } finally {
            if (key != null) {
                DatabaseKeyStore.wipe(key);
            }
        }
    }

    /**
     * Reads every row from a pre-encryption, unencrypted database. Used once by
     * {@link DatabaseProvider} to carry history into the encrypted one.
     */
    static List<MessageEntity> readPlaintext(Context app) {
        if (plaintextInstance == null) {
            plaintextInstance = Room.databaseBuilder(app, AppDatabase.class,
                            DatabaseProvider.DB_NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build();
        }
        List<MessageEntity> out = new ArrayList<>(plaintextInstance.messageDao().getAllForExport());
        Log.i(TAG, "read " + out.size() + " rows from the unencrypted database");
        return out;
    }

    static void closePlaintext() {
        if (plaintextInstance != null) {
            plaintextInstance.close();
            plaintextInstance = null;
        }
    }
}
