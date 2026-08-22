package com.e2eechat.desktop;

import com.e2eechat.core.session.Session;

public interface SessionStateListener {
    void onSessionStateChanged(Session.State state);
}
