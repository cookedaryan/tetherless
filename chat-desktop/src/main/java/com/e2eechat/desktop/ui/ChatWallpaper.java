package com.e2eechat.desktop.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;

/**
 * Telegram's default chat wallpaper: a soft diagonal gradient overlaid with a sparse doodle
 * pattern, both derived from the active theme.
 *
 * <p>The pattern tile is rasterised once and reused through a {@link TexturePaint}; redrawing the
 * doodles per repaint would make scrolling visibly stutter.
 */
public final class ChatWallpaper {

    private static final int TILE = 132;

    private static BufferedImage cachedTile;
    private static boolean cachedForDark;

    private ChatWallpaper() {
    }

    private static BufferedImage tile() {
        if (cachedTile != null && cachedForDark == Theme.isDark()) {
            return cachedTile;
        }
        BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color ink = Theme.chatPattern();
        g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), Theme.isDark() ? 90 : 120));

        // A handful of Telegram-ish doodles scattered across the tile. Positions are fixed so the
        // tile seams stay invisible when it repeats.
        drawHeart(g, 18, 22, 15);
        drawStar(g, 92, 16, 11);
        g.fill(new Ellipse2D.Double(60, 58, 13, 13));
        drawHeart(g, 104, 84, 12);
        drawStar(g, 26, 96, 13);
        g.fill(new Ellipse2D.Double(8, 62, 9, 9));
        drawStar(g, 70, 116, 9);
        g.fill(new Ellipse2D.Double(114, 46, 8, 8));

        g.dispose();
        cachedTile = img;
        cachedForDark = Theme.isDark();
        return img;
    }

    private static void drawHeart(Graphics2D g, double x, double y, double size) {
        GeneralPath p = new GeneralPath();
        double s = size / 16.0;
        p.moveTo(x + 8 * s, y + 15 * s);
        p.curveTo(x - 2 * s, y + 7 * s, x + 2 * s, y - 1 * s, x + 8 * s, y + 4 * s);
        p.curveTo(x + 14 * s, y - 1 * s, x + 18 * s, y + 7 * s, x + 8 * s, y + 15 * s);
        p.closePath();
        g.fill(p);
    }

    private static void drawStar(Graphics2D g, double cx, double cy, double r) {
        GeneralPath p = new GeneralPath();
        for (int i = 0; i < 10; i++) {
            double angle = Math.PI / 5 * i - Math.PI / 2;
            double radius = (i % 2 == 0) ? r : r * 0.45;
            double px = cx + Math.cos(angle) * radius;
            double py = cy + Math.sin(angle) * radius;
            if (i == 0) {
                p.moveTo(px, py);
            } else {
                p.lineTo(px, py);
            }
        }
        p.closePath();
        g.fill(p);
    }

    /** Fills {@code (0, 0, w, h)} of {@code g} with the wallpaper. */
    public static void paint(Graphics2D g, int w, int h) {
        Color base = Theme.chatBg();
        Color tint = Theme.isDark()
                ? new Color(Math.min(255, base.getRed() + 10),
                            Math.min(255, base.getGreen() + 12),
                            Math.min(255, base.getBlue() + 18))
                : new Color(Math.max(0, base.getRed() - 14),
                            Math.max(0, base.getGreen() - 10),
                            Math.max(0, base.getBlue() - 4));

        g.setPaint(new LinearGradientPaint(
                0, 0, w, h,
                new float[]{0f, 1f},
                new Color[]{base, tint}));
        g.fillRect(0, 0, w, h);

        Graphics2D g2 = (Graphics2D) g.create();
        // Rotating the tile hides the grid regularity, the way Telegram's pattern does.
        g2.setPaint(new TexturePaint(tile(), new java.awt.Rectangle(0, 0, TILE, TILE)));
        g2.transform(AffineTransform.getRotateInstance(Math.toRadians(-12), w / 2.0, h / 2.0));
        int over = Math.max(w, h);
        g2.fillRect(-over, -over, over * 3, over * 3);
        g2.dispose();
    }
}
