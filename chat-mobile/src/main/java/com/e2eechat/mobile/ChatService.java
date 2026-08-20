package com.e2eechat.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import com.e2eechat.core.models.Message;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import java.net.Socket;

public class ChatService extends Service {
    private Socket socket;
    private FrameWriter out;
    private FrameReader in;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                socket = new Socket("10.0.2.2", 8080); // Android emulator host IP
                out = new FrameWriter(socket.getOutputStream());
                in = new FrameReader(socket.getInputStream());
                
                out.writeMessage(new com.e2eechat.core.models.MessageBuilder().setType(com.e2eechat.core.models.MessageType.HELLO).setSenderId("mobileUser").setMessageId(java.util.UUID.randomUUID().toString()).setTimestamp(System.currentTimeMillis()).buildUnsigned());
                

                while (true) {
                    Message msg = in.readMessage();
                    if (msg.getType() == com.e2eechat.core.models.MessageType.TEXT_MESSAGE) {
                        // Handle incoming message
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("ChatService", "Error", e);
            }
        }).start();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

