package com.e2eechat.desktop.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;

/**
 * A strip across the top of the window saying a newer version exists.
 *
 * <p>Hidden until there is something to say, and dismissible. It never takes focus, never opens a
 * dialog and never interrupts what the user is doing: a version notice is not worth a modal, and
 * one that had to be dealt with before typing would be worse than none.
 */
public final class UpdateBanner extends JPanel {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateBanner.class);

    private static final int HEIGHT = 34;

    private final JLabel message = new JLabel();
    private final JLabel dismiss = new JLabel("✕");

    private String releaseUrl;

    public UpdateBanner() {
        setLayout(new BorderLayout());
        setVisible(false);
        setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 12));
        setPreferredSize(new Dimension(0, HEIGHT));

        message.setFont(message.getFont().deriveFont(Font.PLAIN, 12f));
        message.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        message.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openReleasePage();
            }
        });

        dismiss.setFont(dismiss.getFont().deriveFont(Font.PLAIN, 12f));
        dismiss.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        dismiss.setToolTipText("Dismiss");
        dismiss.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setVisible(false);
                revalidate();
            }
        });

        add(message, BorderLayout.CENTER);
        add(dismiss, BorderLayout.EAST);

        Theme.addListener(this::applyTheme);
        applyTheme();
    }

    /** Shows the notice. Safe to call with anything; a blank url simply leaves the link inert. */
    public void show(String version, String url) {
        this.releaseUrl = url;
        message.setText("Tetherless " + version + " is available. Click to open the release page.");
        message.setToolTipText(url);
        setVisible(true);
        revalidate();
        repaint();
    }

    private void openReleasePage() {
        if (releaseUrl == null || releaseUrl.isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(releaseUrl));
            }
        } catch (Exception e) {
            // Nothing useful to tell the user: the url is in the tooltip either way.
            LOG.debug("Could not open the release page: {}", e.toString());
        }
    }

    private void applyTheme() {
        setBackground(Theme.headerBg());
        message.setForeground(Theme.accent());
        dismiss.setForeground(Theme.textSecondary());
    }
}
