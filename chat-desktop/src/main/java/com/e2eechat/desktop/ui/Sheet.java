package com.e2eechat.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/**
 * The furniture every side panel is built from: a scrolling column of titled sections and rows.
 *
 * <p>This was private mechanism inside {@code SettingsPanel} back when settings was the only sheet
 * in the application. Splitting the drawer's destinations apart made four panels want the same
 * row - so it moves here rather than being copied four times.
 */
public final class Sheet {

    private Sheet() {
    }

    /** A vertical body to add sections and rows to. */
    public static JPanel column() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 0));
        return body;
    }

    /** Wraps a body so it scrolls when it outgrows the panel. */
    public static JScrollPane scroll(JComponent body) {
        JScrollPane scroll = new JScrollPane(body);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    /** A section heading, in the accent colour. */
    public static JComponent title(String text) {
        JLabel label = new JLabel(text.toUpperCase(Locale.ROOT));
        label.setFont(Theme.font(Font.BOLD, 11.5f));
        label.setForeground(Theme.accent());
        label.setBorder(BorderFactory.createEmptyBorder(18, 20, 8, 20));
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(label, BorderLayout.WEST);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                label.getPreferredSize().height + 26));
        return holder;
    }

    /** A row that states something, optionally with a control on the right. */
    public static Row row(Icon icon, String title, String subtitle, JComponent control) {
        return new Row(icon, title, subtitle, control, null);
    }

    /** A row that goes somewhere when clicked. */
    public static Row navRow(Icon icon, String title, String subtitle, Runnable onClick) {
        return new Row(icon, title, subtitle, null, onClick);
    }

    public static JComponent spacer(int height) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(1, height));
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return spacer;
    }

    /** Stops a {@code BoxLayout} column from stretching a child vertically. */
    public static JComponent fullWidth(JComponent child) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                child.getPreferredSize().height));
        holder.add(child, BorderLayout.CENTER);
        return holder;
    }

    /** One line of a sheet: icon, title, wrapping explanation, and a control or an action. */
    public static final class Row extends JPanel {

        private float hover;
        private Motion.Handle handle = Motion.Handle.COMPLETED;

        private Row(Icon icon, String title, String subtitle, JComponent control,
                    final Runnable onClick) {
            setOpaque(false);
            setLayout(new BorderLayout(14, 0));
            setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));

            add(glyph(icon), BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(Theme.font(Font.PLAIN, 14.5f));
            titleLabel.setForeground(Theme.textPrimary());
            titleLabel.setAlignmentX(LEFT_ALIGNMENT);
            text.add(titleLabel);

            if (subtitle != null && !subtitle.isEmpty()) {
                text.add(Box.createVerticalStrut(2));
                // Left-aligned and genuinely wrapping. The previous row drew one clipped line at a
                // hardcoded height, which cut the longest explanations off mid-sentence.
                WrappedLabel sub = new WrappedLabel(subtitle, 12f, true, true);
                sub.setAlignmentX(LEFT_ALIGNMENT);
                text.add(sub);
            }
            add(text, BorderLayout.CENTER);

            if (control != null) {
                JPanel holder = new JPanel(new GridBagLayout());
                holder.setOpaque(false);
                holder.add(control);
                add(holder, BorderLayout.EAST);
            }

            if (onClick != null) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (onClick != null && contains(e.getPoint())) {
                        onClick.run();
                    }
                }
            });
        }

        private JComponent glyph(final Icon icon) {
            return new JComponent() {
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
        }

        private void fade(float target) {
            final float from = hover;
            handle.cancel();
            handle = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, new Motion.Frame() {
                @Override
                public void at(float progress) {
                    hover = Motion.lerp(from, target, progress);
                    repaint();
                }
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
}
