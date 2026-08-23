package com.e2eechat.mobile;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(MessageEntity message);

    @Query("SELECT * FROM messages ORDER BY sentAt ASC")
    LiveData<List<MessageEntity>> getAllMessages();
    
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY sentAt ASC LIMIT :limit OFFSET :offset")
    LiveData<List<MessageEntity>> getConversation(String conversationId, int limit, int offset);
    
    @Query("UPDATE messages SET deliveryState = :state WHERE messageId = :messageId")
    void updateDeliveryState(String messageId, String state);
    @Query("SELECT * FROM messages WHERE deliveryState = :state ORDER BY sentAt ASC")
    List<MessageEntity> getPendingMessages(String state);

    /** Synchronous full read, used once when carrying a plaintext database into an encrypted one. */
    @Query("SELECT * FROM messages ORDER BY sentAt ASC")
    List<MessageEntity> getAllForExport();
}
