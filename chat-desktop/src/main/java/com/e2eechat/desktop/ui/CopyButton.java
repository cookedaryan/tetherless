package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** A button that copies a string and confirms in place, instead of raising a dialog nobody reads. */
public final class CopyButton extends JComponent {

    private final String value;
    private final String label;
    private float confirmed;
    private float hover;
    private Motion.Handle hoverHandle = Motion.Handle.COMPLETED;
    private Motion.Handle confirmHandle = Motion.Handle.COMPLETED;

    public CopyButton(String value, String label) {
        this.value = value;
        this.label = label;
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
        final float from = hover;
        final float to = in ? 1f : 0f;
        hoverHandle.cancel();
        hoverHandle = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, new Motion.Frame() {
            @Override
            public void at(float progress) {
                hover = Motion.lerp(from, to, progress);
                repaint();
            }
        }, null);
    }

    private void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(value), null);
        confirmHandle.cancel();
        confirmHandle = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, new Motion.Frame() {
            @Override
            public void at(float progress) {
                confirmed = progress;
                repaint();
            }
        }, new Runnable() {
            @Override
            public void run() {
                Timer hold = new Timer(1400, new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        confirmHandle.cancel();
                        confirmHandle = Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT,
                                new Motion.Frame() {
                                    @Override
                                    public void at(float progress) {
                                        confirmed = 1f - progress;
                                        repaint();
                                    }
                                }, null);
                    }
                });
                hold.setRepeats(false);
                hold.start();
            }
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

            String text = confirmed > 0.5f ? "Copied" : label;
            g2.setFont(Theme.font(Font.BOLD, 13f));
            g2.setColor(confirmed > 0.5f ? Theme.accent() : Theme.textPrimary());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (w - fm.stringWidth(text)) / 2f,
                    (h + fm.getAscent() - fm.getDescent()) / 2f);
        } finally {
            g2.dispose();
        }
    }
}
