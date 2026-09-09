package com.e2eechat.desktop;

import com.e2eechat.core.build.BuildInfo;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.TlsSupport;
import com.e2eechat.desktop.ui.Sheet;
import com.e2eechat.desktop.ui.TgIcons;

import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * What the client is connected to, and what it is trusting to get there.
 *
 * <p>"Which certificate am I pinning" is a security question a user is entitled to a straight
 * answer to, and burying it three sections down a settings list was not one.
 */
public class RelayPanel extends JPanel {

    public RelayPanel(ChatClient client) {
        setLayout(new BorderLayout());
        setOpaque(false);

        JPanel body = Sheet.column();

        body.add(Sheet.title("Connection"));
        body.add(Sheet.row(TgIcons.plug(19), "Relay", client.getRelayDescription(), null));
        body.add(Sheet.row(TgIcons.lock(19), "Status",
                describe(client.getConnectionState()), null));

        body.add(Sheet.title("Trust"));
        body.add(Sheet.row(TgIcons.key(19), "Certificate", trustDescription(), null));

        add(Sheet.scroll(body), BorderLayout.CENTER);
    }

    /**
     * The relay is deliberately dumb and stores nothing, so there is no "reconnect now" button
     * here: the transport already retries with backoff on its own, and a manual control would
     * only let a user interrupt a schedule that is working.
     */
    private static String describe(ConnectionState state) {
        switch (state) {
            case CONNECTED:
                return "Connected";
            case CONNECTING:
                return "Connecting…";
            case RECONNECTING:
                return "Reconnecting — retries slow down as they repeat";
            default:
                return "Not connected";
        }
    }

    private static String trustDescription() {
        String configured = TlsSupport.configuredTrustStorePath();
        if (configured != null) {
            return "Pinned to " + shorten(configured);
        }
        return BuildInfo.isRelease()
                ? "Not configured — the app will not connect"
                : "Development certificate — not secure";
    }

    /** Keeps a long path readable in a fixed-width row. */
    private static String shorten(String path) {
        if (path.length() <= 40) {
            return path;
        }
        return "…" + path.substring(path.length() - 39);
    }
}
