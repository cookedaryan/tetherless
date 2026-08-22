package com.e2eechat.core.network;

import com.e2eechat.core.session.Session;

public interface SessionStateListener {
    void onSessionStateChanged(Session.State state);
}
