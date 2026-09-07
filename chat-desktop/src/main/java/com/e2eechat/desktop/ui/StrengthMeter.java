package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * A bar showing roughly how much work a passphrase would cost to guess.
 *
 * <h2>On what this does and does not claim</h2>
 * This is a <strong>heuristic</strong>: length, character variety, and a check against a short list
 * of obvious choices. It is not an entropy measurement and it cannot recognise that
 * {@code correcthorsebatterystaple} is famous or that {@code Tetherless2024!} follows the pattern
 * every password policy produces. A real estimator needs a dictionary and pattern matching.
 *
 * <p>It is still worth showing. On this screen the passphrase is the <em>only</em> thing standing
 * between someone holding the database file and reading it - the key is stretched with PBKDF2 at
 * 210,000 iterations, and no iteration count rescues a guessable passphrase. A meter that moves as
 * the user types is the one moment they will ever think about that. The label is deliberately
 * hedged for the same reason: it says "weak" with confidence and "strong" without.
 */
public class StrengthMeter extends JComponent {

    private static final int BAR_HEIGHT = 4;
    private static final int HEIGHT = 26;

    /** Choices common enough that no length makes them safe. */
    private static final String[] OBVIOUS = {
        "password", "passphrase", "qwerty", "letmein", "welcome", "admin",
        "tetherless", "telegram", "secret", "123456", "iloveyou", "changeit",
    };

    private float reveal;       // animated height, 0 when there is nothing to report
    private float score;        // animated, 0 to 1
    private float targetScore;
    private String caption = "";
    private Motion.Handle animation;

    public StrengthMeter() {
        setOpaque(false);
    }

    /** Recomputes from the current passphrase. */
    public void evaluate(char[] passphrase) {
        int length = passphrase == null ? 0 : passphrase.length;
        float computed = length == 0 ? 0f : rate(passphrase);
        String newCaption;
        if (length == 0) {
            newCaption = "";
        } else if (computed < 0.34f) {
            newCaption = "Weak — easily guessed";
        } else if (computed < 0.67f) {
            newCaption = "Better — longer is stronger";
        } else {
            newCaption = "Long and varied";
        }

        boolean wantVisible = !newCaption.isEmpty();
        caption = newCaption;
        if ((reveal > 0.5f) != wantVisible) {
            float fromReveal = reveal;
            Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, progress -> {
                reveal = Motion.lerp(fromReveal, wantVisible ? 1f : 0f, progress);
                revalidate();
                repaint();
            }, null);
        }
        if (computed == targetScore) {
            repaint();
            return;
        }
        targetScore = computed;

        if (animation != null) {
            animation.cancel();
        }
        float from = score;
        animation = Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, progress -> {
            score = Motion.lerp(from, targetScore, progress);
            repaint();
        }, null);
    }

    /**
     * Length dominates, variety helps, an obvious choice is capped.
     *
     * <p>Weighted this way on purpose: adding a digit and a capital to a short passphrase is what
     * password rules ask for and barely helps, while adding words helps enormously.
     */
    private static float rate(char[] passphrase) {
        String lower = new String(passphrase).toLowerCase(java.util.Locale.ROOT);
        for (String obvious : OBVIOUS) {
            if (lower.contains(obvious)) {
                return Math.min(0.25f, passphrase.length / 80f);
            }
        }

        boolean lowerCase = false;
        boolean upperCase = false;
        boolean digit = false;
        boolean symbol = false;
        for (char c : passphrase) {
            if (Character.isLowerCase(c)) {
                lowerCase = true;
            } else if (Character.isUpperCase(c)) {
                upperCase = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        int classes = (lowerCase ? 1 : 0) + (upperCase ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);

        // Full marks needs roughly 20 characters; variety contributes a quarter at most.
        float lengthPart = Math.min(1f, passphrase.length / 20f) * 0.75f;
        float varietyPart = (classes / 4f) * 0.25f;
        return Math.min(1f, lengthPart + varietyPart);
    }

    private Color colourFor(float value) {
        if (value < 0.34f) {
            return Theme.danger();
        }
        if (value < 0.67f) {
            return new Color(0xE8A33D);
        }
        return new Color(0x4DBD5C);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, Math.round(HEIGHT * reveal));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, Math.round(HEIGHT * reveal));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (reveal <= 0.01f) {
                return;
            }
            g2.setComposite(java.awt.AlphaComposite.getInstance(
                    java.awt.AlphaComposite.SRC_OVER, Math.min(1f, reveal)));

            int w = getWidth();
            g2.setColor(Theme.divider());
            g2.fill(new RoundRectangle2D.Float(0, 0, w, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT));

            if (score > 0f) {
                g2.setColor(colourFor(score));
                g2.fill(new RoundRectangle2D.Float(0, 0, w * score, BAR_HEIGHT,
                        BAR_HEIGHT, BAR_HEIGHT));
            }

            if (!caption.isEmpty()) {
                g2.setFont(Theme.font(Font.PLAIN, 11.5f));
                g2.setColor(Theme.textSecondary());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(caption, 0, BAR_HEIGHT + 6 + fm.getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}
