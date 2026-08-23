package com.e2eechat.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import com.e2eechat.core.identity.PeerId;

/**
 * Names peers have asked to be shown as, the mobile counterpart of the desktop's PeerDirectory.
 *
 * <p>Peer ids are derived from identity keys and carry no name, so the label arrives separately in
 * each peer's {@code HELLO} and is remembered here. A peer may change it freely without becoming
 * unreachable.
 *
 * <p><strong>Self-asserted and not proof of anything.</strong> Any peer may claim any name,
 * including one already in use. Only the id and the safety number identify who you are talking to,
 * so the interface should keep the id visible wherever the name might be relied upon.
 */
public final class PeerNames {

    private static final String PREFS = "tetherless-peer-names";

    private PeerNames() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Records a name. Ignores a blank one, so a peer sending none does not erase a known name. */
    public static void put(Context context, String peerId, String displayName) {
        if (peerId == null || displayName == null || displayName.trim().isEmpty()) {
            return;
        }
        prefs(context).edit().putString(peerId, displayName.trim()).apply();
    }

    /** The label for a peer, falling back to a short form of their id when none is known. */
    public static String get(Context context, String peerId) {
        if (peerId == null) {
            return "";
        }
        String known = prefs(context).getString(peerId, null);
        return known != null ? known : PeerId.shortForm(peerId);
    }

    public static boolean isKnown(Context context, String peerId) {
        return peerId != null && prefs(context).contains(peerId);
    }
}
