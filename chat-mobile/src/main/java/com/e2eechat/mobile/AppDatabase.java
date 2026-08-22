package com.e2eechat.mobile;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {MessageEntity.class}, version = 2)
public abstract class AppDatabase extends RoomDatabase {
    public abstract MessageDao messageDao();

    private static volatile AppDatabase INSTANCE;

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // Room does not support dropping columns or changing types easily in SQLite (prior to modern sqlite).
            // But we can create a new table, copy data, drop old, rename new.
            database.execSQL("CREATE TABLE IF NOT EXISTS `messages_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` TEXT, `conversationId` TEXT, `sender` TEXT, `receiver` TEXT, `content` TEXT, `sentAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `direction` TEXT, `deliveryState` TEXT)");
            // Best effort migration from old schema (id, sender, receiver, content(blob), timestamp)
            // But since content was byte[] and is now String, we will just leave the old rows empty or drop them.
            // Since this is a test app, we'll just drop the old data to be safe with the type change.
            database.execSQL("DROP TABLE messages");
            database.execSQL("ALTER TABLE messages_new RENAME TO messages");
            database.execSQL("CREATE UNIQUE INDEX `index_messages_messageId` ON `messages` (`messageId`)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "chat.db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
