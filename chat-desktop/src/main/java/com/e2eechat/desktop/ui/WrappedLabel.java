package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * Text that wraps to the width it is given, centred or left-aligned.
 *
 * <p>{@code JLabel} can do this with HTML, and the HTML renderer's width handling is unreliable
 * enough that two attempts at it here produced text clipped mid-word instead. {@link
 * LineBreakMeasurer} is what {@link MessageBubble} already uses to lay out message text, so this
 * borrows the approach that is known to work in this codebase rather than fighting the markup.
 *
 * <p>Height is derived from the wrapped result, so a {@code BoxLayout} column gives it exactly the
 * rows it needs.
 */
public class WrappedLabel extends JComponent {

    private String text;
    private float fontSize;
    private boolean secondary;
    private boolean leftAligned;
    private int cachedWidth = -1;
    private int cachedHeight;

    public WrappedLabel(String text, float fontSize, boolean secondary) {
        this(text, fontSize, secondary, false);
    }

    public WrappedLabel(String text, float fontSize, boolean secondary, boolean leftAligned) {
        this.text = text;
        this.fontSize = fontSize;
        this.secondary = secondary;
        this.leftAligned = leftAligned;
        setOpaque(false);
    }

    public void setText(String value) {
        this.text = value;
        cachedWidth = -1;
        revalidate();
        repaint();
    }

    private Font font() {
        return Theme.font(Font.PLAIN, fontSize);
    }

    private Color colour() {
        return secondary ? Theme.textSecondary() : Theme.textPrimary();
    }

    /** Lays the text out at {@code width}, returning the lines and setting {@link #cachedHeight}. */
    private List<TextLayout> layout(int width, FontRenderContext frc) {
        List<TextLayout> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || width <= 0) {
            cachedHeight = 0;
            return lines;
        }
        AttributedString attributed = new AttributedString(text);
        attributed.addAttribute(TextAttribute.FONT, font());
        LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), frc);

        float height = 0;
        while (measurer.getPosition() < text.length()) {
            TextLayout line = measurer.nextLayout(width);
            lines.add(line);
            height += line.getAscent() + line.getDescent() + line.getLeading();
        }
        cachedHeight = Math.round(height);
        return lines;
    }

    private void measure(int width) {
        if (width == cachedWidth) {
            return;
        }
        cachedWidth = width;
        layout(width, new FontRenderContext(null, true, true));
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : 320;
        measure(width);
        return new Dimension(width, cachedHeight);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(80, getPreferredSize().height);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        boolean changed = width != getWidth();
        super.setBounds(x, y, width, height);
        if (changed) {
            cachedWidth = -1;
            revalidate();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colour());
            g2.setFont(font());

            List<TextLayout> lines = layout(getWidth(), g2.getFontRenderContext());
            float y = 0;
            for (TextLayout line : lines) {
                y += line.getAscent();
                float x = leftAligned ? 0f : (getWidth() - line.getAdvance()) / 2f;
                line.draw(g2, x, y);
                y += line.getDescent() + line.getLeading();
            }
        } finally {
            g2.dispose();
        }
    }
}
