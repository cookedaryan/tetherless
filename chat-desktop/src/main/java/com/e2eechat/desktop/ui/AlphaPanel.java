package com.e2eechat.desktop.ui;

import javax.swing.JPanel;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A panel that can paint itself and its children at partial opacity and at a vertical offset.
 *
 * <p>Swing has no opacity on a component, so a fade has to be composited by the parent. Doing it
 * here rather than in each component keeps the fading concern out of things whose job is to draw a
 * message bubble or a form field.
 *
 * <p>The offset exists to pair with the fade. Something that only fades in reads as appearing out
 * of nowhere; the same thing rising a few pixels as it fades reads as arriving, which is what makes
 * the difference between "animated" and "fluid". The offset is applied by translating the graphics
 * rather than by moving the component, so it never disturbs the layout of anything around it.
 */
public class AlphaPanel extends JPanel {

    private float alpha = 1f;
    private float translateY;

    public AlphaPanel() {
        setOpaque(false);
    }

    public float getAlpha() {
        return alpha;
    }

    public void setAlpha(float value) {
        float clamped = value < 0f ? 0f : (value > 1f ? 1f : value);
        if (clamped != alpha) {
            alpha = clamped;
            repaint();
        }
    }

    public float getTranslateY() {
        return translateY;
    }

    public void setTranslateY(float value) {
        if (value != translateY) {
            translateY = value;
            repaint();
        }
    }

    /** Fades and rises together, the standard entrance for a surface in this app. */
    public void enter(int durationMs, float riseFrom) {
        setAlpha(0f);
        setTranslateY(riseFrom);
        Motion.animate(durationMs, Motion.Easing.EASE_OUT_QUART, progress -> {
            setAlpha(progress);
            setTranslateY(Motion.lerp(riseFrom, 0f, progress));
        }, null);
    }

    /** Fades and rises with a delay, so a column of these can arrive in sequence. */
    public void enterAfter(int delayMs, int durationMs, float riseFrom) {
        setAlpha(0f);
        setTranslateY(riseFrom);
        javax.swing.Timer delay = new javax.swing.Timer(delayMs, e -> enter(durationMs, riseFrom));
        delay.setRepeats(false);
        delay.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Nothing to paint here; the composite is applied in paint() so it covers children too.
        super.paintComponent(g);
    }

    @Override
    public void paint(Graphics g) {
        if (alpha >= 1f && translateY == 0f) {
            super.paint(g);
            return;
        }
        if (alpha <= 0f) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.translate(0, Math.round(translateY));
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            super.paint(g2);
        } finally {
            g2.dispose();
        }
    }
}
