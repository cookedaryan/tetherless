package com.e2eechat.mobile;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages", indices = {@Index(value = "messageId", unique = true)})
public class MessageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    
    public String messageId;
    public String conversationId;
    
    public String sender;
    public String receiver;
    public String content;
    
    public long sentAt;
    public long receivedAt;
    
    public String direction;
    public String deliveryState;
}
