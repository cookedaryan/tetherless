package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * An on/off switch whose knob slides.
 *
 * <p>A checkbox states a fact; a switch states that flipping it does something now. Every setting
 * this is used for takes effect immediately, so the switch is the honest control. The knob swelling
 * very slightly as it travels is what sells it as a physical object rather than two drawings.
 */
public class ToggleSwitch extends JComponent {

    private static final int WIDTH = 44;
    private static final int HEIGHT = 26;

    private final List<Consumer<Boolean>> listeners = new ArrayList<>();
    private boolean selected;
    private float position;
    private float hover;
    private Motion.Handle animation;

    public ToggleSwitch(boolean initial) {
        selected = initial;
        position = initial ? 1f : 0f;
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
            public void mouseReleased(MouseEvent e) {
                if (contains(e.getPoint()) && isEnabled()) {
                    setSelected(!selected, true);
                }
            }
        });

        getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke("SPACE"), "toggle");
        getActionMap().put("toggle", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (isEnabled()) {
                    setSelected(!selected, true);
                }
            }
        });
    }

    public void onChange(Consumer<Boolean> listener) {
        listeners.add(listener);
    }

    /**
     * A disabled switch stops offering itself.
     *
     * <p>The click and key handlers already refuse while disabled, but the hand cursor and the
     * focus ring went on saying the control was live. A switch that invites a flip and then
     * ignores it reads as a broken app rather than a setting somebody else decided.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(
                enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        setFocusable(enabled);
        repaint();
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean value, boolean notify) {
        if (selected == value) {
            return;
        }
        selected = value;
        if (animation != null) {
            animation.cancel();
        }
        float from = position;
        float to = value ? 1f : 0f;
        animation = Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, progress -> {
            position = Motion.lerp(from, to, progress);
            repaint();
        }, null);

        if (notify) {
            for (Consumer<Boolean> listener : new ArrayList<>(listeners)) {
                listener.accept(value);
            }
        }
    }

    private void animateHover(float target) {
        float from = hover;
        Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
            hover = Motion.lerp(from, target, progress);
            repaint();
        }, null);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(WIDTH, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (!isEnabled()) {
                // Faded rather than recoloured, so it still reads as the same control showing the
                // same value - which it is; it is just not this user's to change.
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            }

            Color off = Theme.isDark() ? Theme.divider() : new Color(0xD5D8DC);
            Color track = Motion.lerp(off, Theme.accent(), position);
            g2.setColor(track);
            g2.fill(new RoundRectangle2D.Float(0, 0, WIDTH, HEIGHT, HEIGHT, HEIGHT));

            if (isFocusOwner()) {
                g2.setColor(Theme.accent());
                g2.setStroke(new java.awt.BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(-3, -3, WIDTH + 6, HEIGHT + 6,
                        HEIGHT + 6, HEIGHT + 6));
            }

            // The knob widens a little mid-travel, which reads as momentum.
            float stretch = (float) Math.sin(position * Math.PI) * 3f;
            float d = HEIGHT - 6;
            float travel = WIDTH - HEIGHT;
            float x = 3 + position * travel;

            g2.setColor(new Color(0, 0, 0, Math.round(28 * (1 - hover * 0.3f))));
            g2.fill(new Ellipse2D.Float(x, 4.5f, d + stretch, d));
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Float(x, 3, d + stretch, d));
        } finally {
            g2.dispose();
        }
    }
}
