package com.e2eechat.desktop.ui;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * A slowly drifting wash of colour, used behind the sign-in surface.
 *
 * <h2>Kept cheap on purpose</h2>
 * Three full-window radial gradients per frame is a lot of fill for decoration. They are drawn into
 * a buffer an eighth of the window's size and scaled up bilinearly instead. The result is
 * indistinguishable - the shapes have no edges and no detail to lose - and it costs about a
 * sixty-fourth of the pixels.
 *
 * <h2>Kept quiet on purpose</h2>
 * The blobs move at around one screen-width per two minutes, which is below the speed at which the
 * eye tracks motion, so it registers as depth rather than as something happening. Anything faster
 * competes with the form in front of it. Under {@link Motion#isReducedMotion()} it renders one
 * static composition and never animates.
 */
public class AuroraBackground extends JPanel {

    private static final int DOWNSCALE = 8;
    /** One full cycle. Long enough that the motion is never the thing being looked at. */
    private static final int CYCLE_MS = 120_000;

    private float phase;
    private BufferedImage buffer;

    public AuroraBackground() {
        setOpaque(true);
        if (!Motion.isReducedMotion()) {
            drift();
        }
        Theme.follow(this, this::repaint);
    }

    private void drift() {
        Motion.animate(CYCLE_MS, Motion.Easing.LINEAR, progress -> {
            phase = progress;
            repaint();
        }, () -> {
            if (isDisplayable() && !Motion.isReducedMotion()) {
                drift();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        g.setColor(Theme.sidebarBg());
        g.fillRect(0, 0, w, h);

        int bw = Math.max(1, w / DOWNSCALE);
        int bh = Math.max(1, h / DOWNSCALE);
        if (buffer == null || buffer.getWidth() != bw || buffer.getHeight() != bh) {
            buffer = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        }

        Graphics2D bg = buffer.createGraphics();
        try {
            bg.setComposite(java.awt.AlphaComposite.Clear);
            bg.fillRect(0, 0, bw, bh);
            bg.setComposite(java.awt.AlphaComposite.SrcOver);
            bg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            double t = phase * Math.PI * 2;
            // Each blob travels its own ellipse at its own rate, so the composition never repeats
            // in a way the eye can latch onto.
            blob(bg, bw, bh, 0.30 + 0.16 * Math.cos(t), 0.26 + 0.12 * Math.sin(t * 1.3),
                    0.75, Theme.accent(), 0.17f);
            blob(bg, bw, bh, 0.74 + 0.13 * Math.sin(t * 0.8), 0.32 + 0.15 * Math.cos(t * 1.1),
                    0.62, Theme.accentHover(), 0.13f);
            blob(bg, bw, bh, 0.52 + 0.18 * Math.cos(t * 0.6), 0.82 + 0.10 * Math.sin(t),
                    0.85, Theme.accent(), 0.10f);
        } finally {
            bg.dispose();
        }

        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(buffer, 0, 0, w, h, null);
        } finally {
            g2.dispose();
        }
    }

    private void blob(Graphics2D g2, int w, int h, double cx, double cy,
                      double radiusFactor, Color colour, float strength) {
        float radius = (float) (Math.max(w, h) * radiusFactor);
        if (radius <= 0) {
            return;
        }
        Point2D centre = new Point2D.Float((float) (w * cx), (float) (h * cy));
        Color inner = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
                Math.round(255 * strength));
        Color outer = new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 0);
        g2.setPaint(new RadialGradientPaint(centre, radius,
                new float[]{0f, 1f}, new Color[]{inner, outer}));
        g2.fillRect(0, 0, w, h);
    }
}
