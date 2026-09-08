package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.PillButton;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

/**
 * Everything about one conversation, and the safety number in a shape worth comparing.
 *
 * <p>This was a JOptionPane holding a newline-joined string. Two people read a safety number aloud
 * to each other a group at a time; a paragraph of hex defeats that, and it is the only check
 * either of them has against a machine-in-the-middle.
 */
public class ChatInfoPanel extends JPanel {

    /** Monospaced so the groups line up vertically between the two blocks. */
    private static final String MONOSPACED = Font.MONOSPACED;

    private final ChatClient client;
    private final String peerId;

    public ChatInfoPanel(ChatClient client, String peerId, String ownFingerprint,
                         Runnable onSearchRequested) {
        this.client = client;
        this.peerId = peerId;

        setOpaque(false);
        setLayout(new BorderLayout());

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(8, 16, 24, 16));

        body.add(identityBlock());
        body.add(fingerprintBlock("You", ownFingerprint));
        body.add(fingerprintBlock(client.displayNameFor(peerId),
                client.getPeerFingerprint(peerId)));
        body.add(verifiedRow());
        body.add(action("Renegotiate encryption", () -> client.restartSecureChat(peerId)));
        body.add(action("Search this conversation", onSearchRequested));

        add(body, BorderLayout.NORTH);
    }

    /** Name, id, and a copy button — the id is the only thing another person needs. */
    private JComponent identityBlock() {
        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);

        JLabel name = new JLabel(client.displayNameFor(peerId));
        name.setFont(Theme.font(Font.BOLD, 16f));
        name.setForeground(Theme.textPrimary());

        JLabel id = new JLabel(peerId);
        id.setFont(new Font(MONOSPACED, Font.PLAIN, 12));
        id.setForeground(Theme.textSecondary());

        PillButton copy = new PillButton("Copy id", PillButton.Style.QUIET);
        copy.onClick(() -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(peerId), null));

        block.add(name, BorderLayout.NORTH);
        block.add(id, BorderLayout.CENTER);
        block.add(copy, BorderLayout.EAST);
        return block;
    }

    private JComponent fingerprintBlock(String label, String fingerprint) {
        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        JLabel caption = new JLabel(label);
        caption.setFont(Theme.font(Font.PLAIN, 12f));
        caption.setForeground(Theme.textSecondary());

        JLabel digits = new JLabel(groupFingerprint(fingerprint));
        digits.setFont(new Font(MONOSPACED, Font.PLAIN, 13));
        digits.setForeground(Theme.textPrimary());

        block.add(caption, BorderLayout.NORTH);
        block.add(digits, BorderLayout.CENTER);
        return block;
    }

    private JComponent verifiedRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        JLabel label = new JLabel("Verified");
        label.setFont(Theme.font(Font.PLAIN, 14f));
        label.setForeground(Theme.textPrimary());

        ToggleSwitch toggle = new ToggleSwitch(client.getPeerDirectory().isVerified(peerId));
        toggle.onChange(value -> client.getPeerDirectory().setVerified(peerId, value));

        row.add(label, BorderLayout.WEST);
        row.add(toggle, BorderLayout.EAST);
        return row;
    }

    private JComponent action(String label, Runnable onClick) {
        PillButton button = new PillButton(label, PillButton.Style.QUIET);
        button.onClick(onClick);
        return button;
    }

    /** Splits a fingerprint into five-character groups, which is how two people read it aloud. */
    static String groupFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            return "(no key received yet)";
        }
        String compact = fingerprint.replace(":", "").replace(" ", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < compact.length(); i++) {
            if (i > 0 && i % 5 == 0) {
                out.append(' ');
            }
            out.append(compact.charAt(i));
        }
        return out.toString();
    }
}
