package com.e2eechat.desktop;

import com.e2eechat.core.build.BuildInfo;
import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.network.TlsSupport;
import com.e2eechat.desktop.ui.Avatars;
import com.e2eechat.desktop.ui.Motion;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.ToggleSwitch;
import com.e2eechat.desktop.ui.WrappedLabel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The settings surface, presented as a sheet that slides over the window.
 *
 * <h2>Why a sheet rather than a dialog or a menu</h2>
 * These options previously lived as three items in a popup menu, which is where settings go when
 * nobody has decided what the settings are. A popup cannot show a peer id worth copying, cannot
 * group related switches, and gives no room to say what a switch actually does - and here that
 * matters, because "which certificate am I trusting" is a security question a user is entitled to
 * an answer to.
 *
 * <p>A sheet over the window rather than a separate window: settings are a mode of the app, not a
 * second application, and a modal dialog would block the chat behind it from updating.
 */
public class SettingsPanel extends JPanel {

    private final ChatClient client;

    public SettingsPanel(ChatClient client) {
        this.client = client;

        setLayout(new BorderLayout());
        setOpaque(false);
        add(buildBody(), BorderLayout.CENTER);
    }

    // -------------------------------------------------------------------- body

    private JComponent buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 0));

        body.add(buildIdentityCard());

        body.add(sectionTitle("Appearance"));
        body.add(new Row(TgIcons.palette(19), "Night mode",
                "Follows this setting, not the system theme.",
                toggle(Theme.isDark(), value -> Theme.setDark(value))));
        body.add(new Row(TgIcons.settings(19), "Reduce motion",
                "Turns off the animations throughout the app.",
                toggle(Motion.isReducedMotion(), Motion::setReducedMotion)));

        body.add(sectionTitle("Connection"));
        body.add(new Row(TgIcons.plug(19), "Relay",
                client.getRelayDescription(), null));
        body.add(new Row(TgIcons.key(19), "Certificate", trustDescription(), null));

        body.add(sectionTitle("Privacy"));
        body.add(new Row(TgIcons.info(19), "Check for updates on startup",
                "Asks GitHub whether a newer version exists. GitHub, and anyone watching the "
                        + "network, learns this address runs Tetherless and roughly when it "
                        + "started.",
                toggle(DesktopConfig.updateChecksPreference(), value -> {
                    DesktopConfig.setUpdateChecksPreference(value);
                    System.setProperty(UpdateChecker.ENABLED_PROPERTY, String.valueOf(value));
                })));

        body.add(sectionTitle("About"));
        body.add(new Row(TgIcons.info(19), "Version",
                BuildInfo.version() + " · " + BuildInfo.commit() + " · "
                        + BuildInfo.channel(), null));

        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    /**
     * The user's own identity, with the peer id presented as something to copy.
     *
     * <p>The id is the only thing another person needs to start a conversation, so it is the single
     * most-copied string in the application. It gets a button rather than a menu item.
     */
    private JComponent buildIdentityCard() {
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(22, 20, 18, 20));

        final String name = client.getLocalDisplayName();
        JComponent avatar = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(76, 76);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 76);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Avatars.paint(g2, client.getClientId(), name, (getWidth() - 76) / 2, 0, 76);
                g2.dispose();
            }
        };
        card.add(avatar);
        card.add(spacer(14));

        JLabel nameLabel = new JLabel(name, JLabel.CENTER);
        nameLabel.setFont(Theme.font(Font.BOLD, 18f));
        nameLabel.setForeground(Theme.textPrimary());
        nameLabel.setAlignmentX(CENTER_ALIGNMENT);
        card.add(fullWidth(nameLabel));
        card.add(spacer(6));

        JLabel idLabel = new JLabel(PeerId.forDisplay(client.getClientId()), JLabel.CENTER);
        idLabel.setFont(Theme.font(Font.PLAIN, 12.5f));
        idLabel.setForeground(Theme.textSecondary());
        card.add(fullWidth(idLabel));
        card.add(spacer(14));

        CopyButton copy = new CopyButton(client.getClientId());
        card.add(fullWidth(copy));
        card.add(spacer(10));

        WrappedLabel note = new WrappedLabel(
                "Your id comes from your identity key, so renaming yourself does not change it.",
                11.5f, true);
        card.add(note);

        return card;
    }

    /** A button that confirms in place instead of raising a dialog nobody reads. */
    private static class CopyButton extends JComponent {
        private final String value;
        private float confirmed;
        private float hover;

        CopyButton(String value) {
            this.value = value;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    animate(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    animate(false);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    copy();
                }
            });
        }

        private void animate(boolean in) {
            float from = hover;
            Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
                hover = Motion.lerp(from, in ? 1f : 0f, progress);
                repaint();
            }, null);
        }

        private void copy() {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(value), null);
            Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
                confirmed = progress;
                repaint();
            }, () -> {
                javax.swing.Timer hold = new javax.swing.Timer(1400, e ->
                        Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, p -> {
                            confirmed = 1f - p;
                            repaint();
                        }, null));
                hold.setRepeats(false);
                hold.start();
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(160, 36);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 36);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                g2.setColor(Motion.lerp(Theme.inputBg(), Theme.divider(), hover));
                g2.fillRoundRect(0, 0, w, h, h, h);

                String label = confirmed > 0.5f ? "Copied" : "Copy my id";
                g2.setFont(Theme.font(Font.BOLD, 13f));
                g2.setColor(confirmed > 0.5f ? Theme.accent() : Theme.textPrimary());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                g2.drawString(label, (w - fm.stringWidth(label)) / 2f,
                        (h + fm.getAscent() - fm.getDescent()) / 2f);
            } finally {
                g2.dispose();
            }
        }
    }

    /** One settings line: icon, title, explanation, and an optional control. */
    private static class Row extends JPanel {
        private float hover;

        Row(Icon icon, String title, String subtitle, JComponent control) {
            setOpaque(false);
            setLayout(new BorderLayout(14, 0));
            setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));

            JComponent glyph = new JComponent() {
                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(24, 24);
                }

                @Override
                protected void paintComponent(Graphics g) {
                    icon.paintIcon(this, g, 0, 3);
                }

                @Override
                public Color getForeground() {
                    return Theme.icon();
                }
            };
            add(glyph, BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(Theme.font(Font.PLAIN, 14.5f));
            titleLabel.setForeground(Theme.textPrimary());
            titleLabel.setAlignmentX(LEFT_ALIGNMENT);
            text.add(titleLabel);

            if (subtitle != null && !subtitle.isEmpty()) {
                text.add(javax.swing.Box.createVerticalStrut(2));
                WrappedLabel sub = new WrappedLabel(subtitle, 12f, true) {
                    @Override
                    protected void paintComponent(Graphics g) {
                        // Section rows read left-aligned; centring is for the identity card only.
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        g2.setColor(Theme.textSecondary());
                        g2.setFont(Theme.font(Font.PLAIN, 12f));
                        g2.drawString(subtitle, 0, g2.getFontMetrics().getAscent());
                        g2.dispose();
                    }

                    @Override
                    public Dimension getPreferredSize() {
                        return new Dimension(10, 17);
                    }

                    @Override
                    public Dimension getMaximumSize() {
                        return new Dimension(Integer.MAX_VALUE, 17);
                    }
                };
                sub.setAlignmentX(LEFT_ALIGNMENT);
                text.add(sub);
            }
            add(text, BorderLayout.CENTER);

            if (control != null) {
                JPanel holder = new JPanel(new java.awt.GridBagLayout());
                holder.setOpaque(false);
                holder.add(control);
                add(holder, BorderLayout.EAST);
            }

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    fade(1f);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    fade(0f);
                }
            });
        }

        private void fade(float target) {
            float from = hover;
            Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
                hover = Motion.lerp(from, target, progress);
                repaint();
            }, null);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hover > 0.01f) {
                g.setColor(Motion.lerp(Theme.sidebarBg(), Theme.sidebarHover(), hover));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            super.paintComponent(g);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static ToggleSwitch toggle(boolean initial, Consumer<Boolean> onChange) {
        ToggleSwitch control = new ToggleSwitch(initial);
        control.onChange(onChange);
        return control;
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

    private static JComponent sectionTitle(String text) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(Theme.font(Font.BOLD, 11.5f));
        label.setForeground(Theme.accent());
        label.setBorder(BorderFactory.createEmptyBorder(18, 20, 8, 20));
        label.setAlignmentX(LEFT_ALIGNMENT);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(label, BorderLayout.WEST);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                label.getPreferredSize().height + 26));
        return holder;
    }

    private static JComponent spacer(int height) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(1, height));
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return spacer;
    }

    private static JComponent fullWidth(JComponent child) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                child.getPreferredSize().height));
        holder.add(child, BorderLayout.CENTER);
        return holder;
    }
}
