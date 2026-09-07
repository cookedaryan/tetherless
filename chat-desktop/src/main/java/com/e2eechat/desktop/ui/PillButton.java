package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The primary action control: a filled pill that responds to the pointer and can show it is busy.
 *
 * <h2>Why not a JButton</h2>
 * A themed {@code JButton} can be made to look right, but its hover and pressed states are switched
 * by the look-and-feel with no interpolation, and its painting is entangled with the border and
 * content-area flags. Painting it here is less code than fighting that, and it makes the busy state
 * - a spinner in place of the label, with the pill kept at the same width - straightforward.
 *
 * <h2>Why the press is a scale rather than a colour</h2>
 * Darkening on press is invisible on a control that is already saturated. A one-percent contraction
 * reads instantly as a physical push, and returns on release with a slight overshoot, which is what
 * makes it feel responsive rather than merely animated.
 */
public class PillButton extends JComponent {

    /** How the button is filled. */
    public enum Style {
        /** Accent fill, white label. For the one action the surface exists to perform. */
        PRIMARY,
        /** Transparent with a hairline, for a secondary or dismissive action. */
        QUIET
    }

    private static final int HEIGHT = 46;
    private static final int MIN_WIDTH = 120;

    private final List<Runnable> listeners = new ArrayList<>();
    private String text;
    private Style style;

    private float hover;
    private float press;
    private float busy;
    private boolean isBusy;
    private float spinnerAngle;

    private Motion.Handle hoverAnimation;
    private Motion.Handle pressAnimation;
    private Motion.Handle spinner;

    public PillButton(String text, Style style) {
        this.text = text;
        this.style = style;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(true);

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                animateHover(1f);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                animateHover(0f);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                animatePress(1f);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                animatePress(0f);
                if (contains(e.getPoint()) && isEnabled() && !isBusy) {
                    fire();
                }
            }
        });

        // A control reachable only by pointer is a control some people cannot reach.
        getInputMap(WHEN_FOCUSED).put(
                javax.swing.KeyStroke.getKeyStroke("SPACE"), "activate");
        getInputMap(WHEN_FOCUSED).put(
                javax.swing.KeyStroke.getKeyStroke("ENTER"), "activate");
        getActionMap().put("activate", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isEnabled() && !isBusy) {
                    fire();
                }
            }
        });
    }

    public void onClick(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    public void setText(String value) {
        this.text = value;
        repaint();
    }

    public void setStyle(Style value) {
        this.style = value;
        repaint();
    }

    /**
     * Shows or hides the spinner.
     *
     * <p>The label is kept in the layout while busy so the pill does not change width, which would
     * make the surrounding form jump at exactly the moment the user is waiting on it.
     */
    public void setBusy(boolean value) {
        if (isBusy == value) {
            return;
        }
        isBusy = value;
        if (spinner != null) {
            spinner.cancel();
        }
        float from = busy;
        Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
            busy = Motion.lerp(from, value ? 1f : 0f, progress);
            repaint();
        }, null);

        if (value) {
            spin();
        }
    }

    private void spin() {
        // One revolution per second, restarted rather than looped so reduced motion can stop it.
        spinner = Motion.animate(1000, Motion.Easing.LINEAR, progress -> {
            spinnerAngle = progress * 360f;
            repaint();
        }, () -> {
            if (isBusy) {
                spin();
            }
        });
    }

    public boolean isBusy() {
        return isBusy;
    }

    private void animateHover(float target) {
        if (!isEnabled()) {
            return;
        }
        if (hoverAnimation != null) {
            hoverAnimation.cancel();
        }
        float from = hover;
        hoverAnimation = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
            hover = Motion.lerp(from, target, progress);
            repaint();
        }, null);
    }

    private void animatePress(float target) {
        if (pressAnimation != null) {
            pressAnimation.cancel();
        }
        float from = press;
        // Releasing overshoots very slightly, the way a real button springs back.
        Motion.Easing easing = target == 0f ? Motion.Easing.EASE_OUT_BACK : Motion.Easing.EASE_OUT;
        pressAnimation = Motion.animate(Motion.INSTANT, easing, progress -> {
            press = Motion.lerp(from, target, progress);
            repaint();
        }, null);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(Theme.font(Font.BOLD, 14.5f));
        int width = Math.max(MIN_WIDTH, fm.stringWidth(text) + 56);
        return new Dimension(width, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            float shrink = press * 1.5f;
            float x = shrink;
            float y = shrink;
            float w = getWidth() - shrink * 2;
            float h = getHeight() - shrink * 2;
            float arc = h;

            boolean enabled = isEnabled();
            if (style == Style.PRIMARY) {
                Color base = Motion.lerp(Theme.accent(), Theme.accentHover(), hover);
                if (!enabled) {
                    base = Motion.lerp(base, Theme.inputBg(), 0.72f);
                }
                g2.setColor(base);
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
            } else {
                g2.setColor(Motion.lerp(new Color(0, 0, 0, 0), Theme.inputBg(), hover));
                g2.fill(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
                g2.setColor(Theme.divider());
                g2.setStroke(new java.awt.BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, arc, arc));
            }

            if (isFocusOwner()) {
                g2.setColor(Theme.accent());
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(x - 3, y - 3, w + 6, h + 6, arc, arc));
            }

            Color label = style == Style.PRIMARY ? Color.WHITE : Theme.textPrimary();
            if (!enabled) {
                label = Theme.textSecondary();
            }

            if (busy < 1f) {
                g2.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, 1f - busy));
                g2.setColor(label);
                g2.setFont(Theme.font(Font.BOLD, 14.5f));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text,
                        (getWidth() - fm.stringWidth(text)) / 2f,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
            }

            if (busy > 0f) {
                g2.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, busy));
                float d = 18f;
                g2.setColor(label);
                g2.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g2.draw(new Arc2D.Float((getWidth() - d) / 2f, (getHeight() - d) / 2f, d, d,
                        -spinnerAngle, 270, Arc2D.OPEN));
            }
        } finally {
            g2.dispose();
        }
    }
}
