package com.e2eechat.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.network.ConnectionManager;
import com.e2eechat.core.network.MessageListener;

public class ChatService extends Service implements MessageListener {
    private static final String CHANNEL_ID = "ChatServiceChannel";
    private final IBinder binder = new LocalBinder();
    
    private ConnectionManager connectionManager;
    private String clientId = "mobile_client_" + System.currentTimeMillis();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public class LocalBinder extends Binder {
        ChatService getService() {
            return ChatService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("E2EE Chat")
                .setContentText("Listening for messages...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        
        startForeground(1, notification);
        
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "E2EEChat::NetworkWakeLock");
        }
        
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "E2EEChat::NetworkWifiLock");
        }

        connectionManager = new ConnectionManager("10.0.2.2", 8000, clientId, this);

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.d("ChatService", "Network onAvailable -> starting connectionManager");
                    connectionManager.start();
                }

                @Override
                public void onLost(Network network) {
                    Log.d("ChatService", "Network onLost -> stopping connectionManager");
                    connectionManager.stop();
                }
            };
            connectivityManager.registerNetworkCallback(request, networkCallback);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        if (connectionManager != null) {
            connectionManager.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public void onMessageReceived(Message message) {
        if (message.getType() == com.e2eechat.core.models.MessageType.TEXT_MESSAGE) {
            MessageEntity entity = new MessageEntity();
            entity.messageId = message.getMessageId();
            entity.conversationId = message.getSenderId();
            entity.content = new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
            entity.direction = "IN";
            entity.deliveryState = "DELIVERED";
            entity.sentAt = message.getTimestamp();
            entity.receivedAt = System.currentTimeMillis();
            
            DatabaseProvider.getDatabase(this).messageDao().insert(entity);
        }
    }

    private final androidx.lifecycle.MutableLiveData<String> connectionStateLive = new androidx.lifecycle.MutableLiveData<>("DISCONNECTED");

    @Override
    public void onConnectionStateChanged(com.e2eechat.core.network.ConnectionState state) {
        connectionStateLive.postValue(state.name());
        if (state == com.e2eechat.core.network.ConnectionState.CONNECTED) {
            flushPendingMessages();
        }
    }
    
    private void flushPendingMessages() {
        new Thread(() -> {
            try {
                java.util.List<MessageEntity> pending = DatabaseProvider.getDatabase(this)
                        .messageDao().getPendingMessages("PENDING");
                if (pending != null && !pending.isEmpty()) {
                    for (MessageEntity m : pending) {
                        sendTextMessage(m.conversationId, m.content, m.messageId);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public androidx.lifecycle.LiveData<String> getConnectionStateLiveData() {
        return connectionStateLive;
    }

    public void sendTextMessage(String peerId, String text, String messageId) {
        if (connectionManager != null) {
            boolean lockAcquired = false;
            try {
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(10000); // 10 second max hold
                    lockAcquired = true;
                }
                if (wifiLock != null && !wifiLock.isHeld()) {
                    wifiLock.acquire();
                }

                com.e2eechat.core.models.Message msg = new com.e2eechat.core.models.MessageBuilder()
                        .setType(com.e2eechat.core.models.MessageType.TEXT_MESSAGE)
                        .setSenderId(clientId)
                        .setReceiverId(peerId)
                        .setMessageId(messageId)
                        .setTimestamp(System.currentTimeMillis())
                        .setIv(new byte[12])
                        .setPayload(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .buildUnsigned();
                
                connectionManager.sendMessage(msg);
                
                new Thread(() -> {
                    DatabaseProvider.getDatabase(this).messageDao().updateDeliveryState(messageId, "SENT");
                }).start();
                
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (lockAcquired && wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (wifiLock != null && wifiLock.isHeld()) {
                    wifiLock.release();
                }
            }
        }
    }

    public void trustPeer(String peerId) {
        connectionStateLive.postValue("ESTABLISHED");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Chat Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
