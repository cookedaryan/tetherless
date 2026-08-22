package com.e2eechat.desktop.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;

/**
 * Telegram's circular gradient avatars.
 *
 * <p>Telegram assigns each peer one of seven two-stop gradients, chosen deterministically from the
 * peer id so the same person keeps the same colour on every device, and overlays their initials.
 */
public final class Avatars {

    /** The seven Telegram peer-colour gradients, as {top, bottom} pairs. */
    private static final int[][] GRADIENTS = {
            {0xFF885E, 0xFF516A}, // red
            {0xFFCD6A, 0xFFA85C}, // orange
            {0x82B1FF, 0x665FFF}, // violet
            {0xA0DE7E, 0x54CB68}, // green
            {0x53EDD6, 0x28C9B7}, // cyan
            {0x72D5FD, 0x2A9EF1}, // blue
            {0xE0A2F3, 0xD669ED}, // pink
    };

    private Avatars() {
    }

    /**
     * Stable gradient index for a peer.
     *
     * <p>Uses {@code Math.floorMod} rather than {@code Math.abs}: {@code Math.abs} returns a
     * negative value for {@code Integer.MIN_VALUE}, which would throw on the array access for the
     * rare id that hashes to exactly that.
     */
    private static int slot(String peerId) {
        return Math.floorMod(peerId == null ? 0 : peerId.hashCode(), GRADIENTS.length);
    }

    public static Color topColor(String peerId) {
        return new Color(GRADIENTS[slot(peerId)][0]);
    }

    public static Color bottomColor(String peerId) {
        return new Color(GRADIENTS[slot(peerId)][1]);
    }

    /**
     * The letters Telegram shows inside the circle: the initial of the first word, plus the initial
     * of the second where the display name has one.
     */
    public static String initials(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return "?";
        }
        String[] words = displayName.trim().split("[\\s_.-]+");
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(words[0].charAt(0)));
        if (words.length > 1 && !words[1].isEmpty()) {
            sb.append(Character.toUpperCase(words[1].charAt(0)));
        }
        return sb.toString();
    }

    /** Paints a circular avatar of {@code size} px at {@code (x, y)}. */
    public static void paint(Graphics2D g, String peerId, String displayName, int x, int y, int size) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setPaint(new GradientPaint(x, y, topColor(peerId), x, y + size, bottomColor(peerId)));
        g2.fill(new Ellipse2D.Double(x, y, size, size));

        String text = initials(displayName);
        g2.setColor(Color.WHITE);
        g2.setFont(Theme.font(Font.BOLD, size * 0.4f));
        FontMetrics fm = g2.getFontMetrics();
        int tx = x + (size - fm.stringWidth(text)) / 2;
        int ty = y + (size - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(text, tx, ty);

        g2.dispose();
    }

    /** Saved-messages style avatar for the local user's own entry. */
    public static void paintSelf(Graphics2D g, int x, int y, int size) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setPaint(new GradientPaint(x, y, new Color(0x72D5FD), x, y + size, new Color(0x2A9EF1)));
        g2.fill(new Ellipse2D.Double(x, y, size, size));
        g2.dispose();
    }
}
