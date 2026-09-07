package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * What fills the right-hand side before a conversation is open.
 *
 * <h2>Why this replaced a pill of text</h2>
 * The previous empty state was the words "Select a chat to start messaging" in a floating pill,
 * centred on the wallpaper. That is fine as a label and useless as a first screen: a new user
 * arrives here with no conversations to select, no idea that a peer id is the thing they need, and
 * nothing on screen that tells them.
 *
 * <p>So this says what the app is, states the one fact a newcomer needs - conversations start from a
 * peer id - and confirms the property they are here for. The mark drifts by a couple of pixels on a
 * long cycle so the screen is not completely dead, which under reduced motion simply does not
 * happen.
 */
public class WelcomePane extends JComponent {

    private static final int MARK = 92;

    private final String heading;
    private final String body;
    private final boolean secure;

    private float reveal;
    private float bob;

    public WelcomePane(String heading, String body, boolean secure) {
        this.heading = heading;
        this.body = body;
        this.secure = secure;
        setOpaque(false);

        Motion.animate(Motion.DELIBERATE, Motion.Easing.EASE_OUT, progress -> {
            reveal = progress;
            repaint();
        }, this::bob);
    }

    /** A slow rise and fall, restarted each cycle so reduced motion can end it. */
    private void bob() {
        if (Motion.isReducedMotion() || !isDisplayable()) {
            return;
        }
        Motion.animate(5200, Motion.Easing.EASE_IN_OUT, progress -> {
            bob = (float) Math.sin(progress * Math.PI * 2) * 4f;
            repaint();
        }, this::bob);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            ChatWallpaper.paint(g2, getWidth(), getHeight());

            int centreX = getWidth() / 2;
            int blockHeight = MARK + 24 + 30 + 10 + 44 + (secure ? 46 : 0);
            int top = (getHeight() - blockHeight) / 2;

            g2.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, reveal))));

            int markY = Math.round(top + bob + (1f - reveal) * 12f);
            Brandmark.paint(g2, centreX - MARK / 2, markY, MARK, 1f);

            int y = top + MARK + 34;
            g2.setFont(Theme.font(Font.BOLD, 19f));
            g2.setColor(Theme.textPrimary());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(heading, centreX - fm.stringWidth(heading) / 2f, y);

            y += 26;
            g2.setFont(Theme.font(Font.PLAIN, 13.5f));
            g2.setColor(Theme.textSecondary());
            drawWrapped(g2, body, centreX, y, Math.min(380, getWidth() - 80));

            if (secure) {
                y += 62;
                drawSecurePill(g2, centreX, y);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Centres each line; the body is two short sentences, so a simple word wrap is enough. */
    private void drawWrapped(Graphics2D g2, String text, int centreX, int y, int maxWidth) {
        FontMetrics fm = g2.getFontMetrics();
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(candidate) > maxWidth && line.length() > 0) {
                g2.drawString(line.toString(), centreX - fm.stringWidth(line.toString()) / 2f,
                        lineY);
                lineY += fm.getHeight();
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            g2.drawString(line.toString(), centreX - fm.stringWidth(line.toString()) / 2f, lineY);
        }
    }

    /** States the guarantee, because it is the reason anyone would choose this over anything else. */
    private void drawSecurePill(Graphics2D g2, int centreX, int y) {
        String label = "End-to-end encrypted";
        g2.setFont(Theme.font(Font.PLAIN, 12.5f));
        FontMetrics fm = g2.getFontMetrics();
        int iconSize = 14;
        int pillW = fm.stringWidth(label) + 30 + iconSize;
        int pillH = 30;
        int x = centreX - pillW / 2;

        Color accent = Theme.accent();
        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 28));
        g2.fill(new RoundRectangle2D.Float(x, y, pillW, pillH, pillH, pillH));

        TgIcons.tinted(TgIcons.lock(iconSize), accent)
                .paintIcon(this, g2, x + 12, y + (pillH - iconSize) / 2);
        g2.setColor(accent);
        g2.drawString(label, x + 12 + iconSize + 6,
                y + (pillH - fm.getHeight()) / 2f + fm.getAscent());
    }
}
