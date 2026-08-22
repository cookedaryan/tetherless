package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;

public interface MessageListener {
    void onMessageReceived(Message msg);
    void onConnectionStateChanged(ConnectionState state);
}
