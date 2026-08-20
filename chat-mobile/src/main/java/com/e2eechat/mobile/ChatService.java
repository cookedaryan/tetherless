package com.e2eechat.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import com.e2eechat.core.models.Message;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ChatService extends Service {
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                socket = new Socket("10.0.2.2", 8080); // Android emulator host IP
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());
                
                out.writeObject(new Message(Message.MessageType.CONNECT, "mobileUser", null, null));
                out.flush();

                while (true) {
                    Message msg = (Message) in.readObject();
                    if (msg.getType() == Message.MessageType.TEXT_MESSAGE) {
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
