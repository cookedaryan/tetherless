package com.e2eechat.desktop.ui;

import java.awt.Font;
import java.awt.Graphics2D;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.awt.font.TextAttribute;

/**
 * Applies an emoji-capable font to the emoji runs inside a string.
 *
 * <p>Java does not fall back to a colour emoji font on its own: text drawn in "Segoe UI" renders
 * every emoji as a tofu box, which is exactly what the first build of the transcript did. Splitting
 * the string into runs and attaching {@link Theme#emojiFont(float)} to the emoji ones is what makes
 * the picker's output actually visible in a bubble.
 */
public final class EmojiText {

    private EmojiText() {
    }

    /**
     * True for codepoints that need the emoji font.
     *
     * <p>Covers the pictographic blocks plus the older symbol ranges that Unicode later gave emoji
     * presentation, and the variation selector / zero-width joiner that bind sequences together.
     */
    public static boolean isEmoji(int codePoint) {
        return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)   // pictographs, faces, symbols
                || (codePoint >= 0x2600 && codePoint <= 0x27BF) // misc symbols and dingbats
                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF) // arrows and stars
                || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF) // regional indicators (flags)
                || codePoint == 0xFE0F                          // variation selector-16
                || codePoint == 0x200D                          // zero-width joiner
                || (codePoint >= 0x2190 && codePoint <= 0x21FF)
                || (codePoint >= 0x2700 && codePoint <= 0x27BF);
    }

    public static boolean containsEmoji(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isEmoji(cp)) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Builds an {@link AttributedString} over {@code text} with {@code base} applied throughout and
     * the emoji font applied to emoji runs.
     *
     * @param text must not be empty; an empty string has no attribute range to set
     */
    public static AttributedString attributed(String text, Font base) {
        AttributedString as = new AttributedString(text);
        as.addAttribute(TextAttribute.FONT, base);
        if (!containsEmoji(text)) {
            return as;
        }

        Font emojiFont = Theme.emojiFont(base.getSize2D());
        int runStart = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (isEmoji(cp)) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                as.addAttribute(TextAttribute.FONT, emojiFont, runStart, i);
                runStart = -1;
            }
            i += width;
        }
        if (runStart >= 0) {
            as.addAttribute(TextAttribute.FONT, emojiFont, runStart, text.length());
        }
        return as;
    }

    public static AttributedCharacterIterator iterator(String text, Font base) {
        return attributed(text, base).getIterator();
    }

    /**
     * Draws a single line with emoji fallback, for the label-style call sites that would otherwise
     * use {@code Graphics2D.drawString}.
     *
     * @param x left edge
     * @param y baseline
     */
    public static void draw(Graphics2D g, String text, Font base, int x, int y) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!containsEmoji(text)) {
            g.setFont(base);
            g.drawString(text, x, y);
            return;
        }
        g.drawString(iterator(text, base), x, y);
    }
}
