package com.e2eechat.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Relay address and local display name.
 *
 * <p>The host was previously hardcoded to the emulator's loopback alias, which meant the app could
 * only ever talk to a relay on the developer's own machine. It is now configurable, with that alias
 * kept only as the default so an emulator still works out of the box.
 */
public final class MobileConfig {

    private static final String PREFS = "tetherless-config";
    private static final String KEY_HOST = "relay.host";
    private static final String KEY_PORT = "relay.port";
    private static final String KEY_NAME = "display.name";

    /** 10.0.2.2 is how an Android emulator reaches the host machine's loopback. */
    private static final String DEFAULT_HOST = "10.0.2.2";
    private static final int DEFAULT_PORT = 8080;

    private MobileConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String relayHost(Context context) {
        return prefs(context).getString(KEY_HOST, DEFAULT_HOST);
    }

    public static int relayPort(Context context) {
        return prefs(context).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setRelay(Context context, String host, int port) {
        prefs(context).edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply();
    }

    /**
     * The name this device asks to be shown as. Metadata only: the peer id comes from the identity
     * key, so changing this does not change the address anyone routes to.
     */
    public static String displayName(Context context) {
        return prefs(context).getString(KEY_NAME, "Android");
    }

    public static void setDisplayName(Context context, String name) {
        prefs(context).edit().putString(KEY_NAME, name == null ? "" : name.trim()).apply();
    }
}
