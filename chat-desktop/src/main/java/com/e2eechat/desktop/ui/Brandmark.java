package com.e2eechat.desktop.ui;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.RoundRectangle2D;

/**
 * Draws the application mark: a speech bubble with a keyhole, on a rounded accent tile.
 *
 * <p>Deliberately the same shape as the installer icon produced by
 * {@code scripts/AppIconGenerator.java}. A launcher icon and the mark inside the app being subtly
 * different is the kind of thing nobody can name but everybody registers as unfinished.
 *
 * <p>Drawn as vectors rather than loaded from the PNG so it stays crisp at any size and on any
 * display scaling, and so it can be animated - the {@code reveal} parameter scales and settles the
 * tile, which the login uses on first paint.
 */
public final class Brandmark {

    private Brandmark() {
    }

    /**
     * Paints the mark centred in a {@code size} box at {@code (x, y)}.
     *
     * @param reveal 0 to 1; below 1 the mark is scaled down and faded, for an entrance
     */
    public static void paint(Graphics2D g, int x, int y, int size, float reveal) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            if (reveal < 1f) {
                // Scale about the centre so it grows in place rather than drifting from the corner.
                float scale = Motion.lerp(0.82f, 1f, reveal);
                g2.translate(x + size / 2.0, y + size / 2.0);
                g2.scale(scale, scale);
                g2.translate(-(x + size / 2.0), -(y + size / 2.0));
                g2.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, reveal))));
            }

            double s = size;
            Color top = Theme.accent();
            Color bottom = Theme.accentHover();
            g2.setPaint(new GradientPaint(x, y, top, x, (float) (y + s), bottom));
            g2.fill(new RoundRectangle2D.Double(x, y, s, s, s * 0.24, s * 0.24));

            double bw = s * 0.60;
            double bh = s * 0.44;
            double bx = x + (s - bw) / 2.0;
            double by = y + s * 0.22;
            double radius = bh * 0.42;

            Area bubble = new Area(new RoundRectangle2D.Double(bx, by, bw, bh, radius, radius));

            GeneralPath tail = new GeneralPath();
            tail.moveTo(bx + bw * 0.24, by + bh * 0.86);
            tail.lineTo(bx + bw * 0.20, by + bh * 1.42);
            tail.lineTo(bx + bw * 0.56, by + bh * 0.92);
            tail.closePath();
            bubble.add(new Area(tail));

            // Below about 32px the keyhole turns to mush, exactly as in the launcher icon.
            if (size >= 32) {
                double kd = bh * 0.30;
                double kx = bx + (bw - kd) / 2.0;
                double ky = by + bh * 0.24;
                Area keyhole = new Area(new Ellipse2D.Double(kx, ky, kd, kd));

                GeneralPath stem = new GeneralPath();
                stem.moveTo(kx + kd * 0.34, ky + kd * 0.70);
                stem.lineTo(kx + kd * 0.66, ky + kd * 0.70);
                stem.lineTo(kx + kd * 0.80, ky + kd * 1.75);
                stem.lineTo(kx + kd * 0.20, ky + kd * 1.75);
                stem.closePath();
                keyhole.add(new Area(stem));

                bubble.subtract(keyhole);
            }

            g2.setColor(Color.WHITE);
            g2.fill(bubble);
        } finally {
            g2.dispose();
        }
    }
}
