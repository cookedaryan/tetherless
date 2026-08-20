package com.e2eechat.mobile;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "messages")
public class MessageEntity {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String sender;
    public String receiver;
    public byte[] content;
    public long timestamp;
}
