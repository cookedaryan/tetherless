package com.e2eechat.desktop.ui;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.desktop.ChatMessage;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.AttributedString;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A single Telegram message bubble.
 *
 * <p>Reproduces the details that make Telegram's transcript recognisable:
 * <ul>
 *   <li>the bubble is sized to its text, capped at 72% of the viewport, and wraps on word
 *       boundaries;</li>
 *   <li>the timestamp and delivery ticks sit <em>inside</em> the bubble on the last text line when
 *       they fit, and drop to their own line only when they do not — Telegram's "meta inline"
 *       behaviour;</li>
 *   <li>the last bubble in a run from one sender grows a tail; earlier ones stay tail-less and sit
 *       closer together.</li>
 * </ul>
 *
 * <p>This replaces a {@code JLabel}-based renderer, which could not wrap at all — a long message
 * was simply clipped at the panel edge.
 */
public class MessageBubble extends JComponent {

    private static final int PAD_H = 12;
    private static final int PAD_V = 7;
    private static final int RADIUS = 16;
    private static final int TAIL_W = 7;
    private static final int META_GAP = 8;
    private static final int MAX_ABS_WIDTH = 560;
    private static final double MAX_WIDTH_RATIO = 0.72;

    private final ChatMessage message;
    private final boolean outgoing;
    private boolean tail;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

    /** Wrapped text lines, recomputed whenever the available width changes. */
    private final List<TextLayout> lines = new ArrayList<>();
    private int layoutForWidth = -1;
    private int textBlockWidth;
    private int lineHeight;
    private boolean metaOnOwnLine;
    private int replyBlockHeight;

    private boolean hovered;

    public MessageBubble(ChatMessage message, boolean outgoing, boolean tail) {
        this.message = message;
        this.outgoing = outgoing;
        this.tail = tail;
        setOpaque(false);
    }

    public ChatMessage getMessage() {
        return message;
    }

    /**
     * Adds or removes the tail. Set when a later message joins this one's run, so an appended
     * message can demote its predecessor without the transcript rebuilding every row.
     */
    public void setTail(boolean tail) {
        if (this.tail != tail) {
            this.tail = tail;
            layoutForWidth = -1;
            revalidate();
            repaint();
        }
    }

    public void setHovered(boolean hovered) {
        if (this.hovered != hovered) {
            this.hovered = hovered;
            repaint();
        }
    }

    // ----------------------------------------------------------------- layout

    private String metaText() {
        return timeFormat.format(new Date(message.getTimestamp()));
    }

    private Icon statusIcon() {
        if (!outgoing) {
            return null;
        }
        switch (message.getStatus()) {
            case PENDING:
                return TgIcons.clock(13);
            case SENT:
                return TgIcons.check(15);
            case DELIVERED:
            case READ:
                return TgIcons.doubleCheck(15);
            case FAILED:
                return TgIcons.failed(13);
            default:
                return null;
        }
    }

    private int metaWidth(FontMetrics metaFm) {
        int w = metaFm.stringWidth(metaText());
        Icon icon = statusIcon();
        if (icon != null) {
            w += icon.getIconWidth() + 3;
        }
        return w;
    }

    /**
     * Wraps the body text to {@code availableWidth} and works out whether the timestamp fits beside
     * the final line. Results are cached until the available width changes, because
     * {@link LineBreakMeasurer} is far too costly to run on every repaint.
     */
    private void layoutText(int availableWidth) {
        if (layoutForWidth == availableWidth) {
            return;
        }
        layoutForWidth = availableWidth;
        lines.clear();

        int maxBubbleWidth = Math.min((int) (availableWidth * MAX_WIDTH_RATIO), MAX_ABS_WIDTH);
        int maxContentWidth = Math.max(80, maxBubbleWidth - PAD_H * 2);

        Font font = Theme.bubbleText();
        FontRenderContext frc = new FontRenderContext(null, true, true);
        FontMetrics metaFm = getFontMetrics(Theme.bubbleMeta());

        String text = message.getContent() == null ? "" : message.getContent();
        int widest = 0;

        // Blank lines carry no glyphs, so LineBreakMeasurer rejects them; they have to be measured
        // paragraph by paragraph and re-inserted as empty rows.
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                AttributedString blank = new AttributedString(" ");
                blank.addAttribute(TextAttribute.FONT, font);
                lines.add(new TextLayout(blank.getIterator(), frc));
                continue;
            }
            AttributedString attributed = EmojiText.attributed(paragraph, font);
            LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), frc);
            while (measurer.getPosition() < paragraph.length()) {
                TextLayout layout = measurer.nextLayout(maxContentWidth);
                lines.add(layout);
                widest = Math.max(widest, (int) Math.ceil(layout.getAdvance()));
            }
        }

        lineHeight = (int) Math.ceil(font.getLineMetrics("Ag", frc).getHeight());

        int meta = metaWidth(metaFm);
        int lastLine = lines.isEmpty() ? 0 : (int) Math.ceil(lines.get(lines.size() - 1).getAdvance());
        metaOnOwnLine = lastLine + META_GAP + meta > maxContentWidth;

        int contentWidth = metaOnOwnLine
                ? Math.max(widest, meta)
                : Math.max(widest, lastLine + META_GAP + meta);

        if (message.hasReply()) {
            FontMetrics nameFm = getFontMetrics(Theme.font(Font.BOLD, 12.5f));
            FontMetrics previewFm = getFontMetrics(Theme.font(Font.PLAIN, 12.5f));
            replyBlockHeight = nameFm.getHeight() + previewFm.getHeight() + 6;
            int replyWidth = 8 + Math.max(
                    nameFm.stringWidth(shortName(message.getReplyToSender())),
                    previewFm.stringWidth(clip(safe(message.getReplyToPreview()), 48)));
            contentWidth = Math.max(contentWidth, Math.min(replyWidth, maxContentWidth));
        } else {
            replyBlockHeight = 0;
        }

        textBlockWidth = Math.min(contentWidth, maxContentWidth);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /**
     * Label for the author of a quoted message.
     *
     * <p>The stored value is already a resolved display name for messages this client sent, and a
     * peer id for older rows. Ids are shortened rather than printed in full, since a 32-character
     * hex string as a quote header would swamp the quote itself.
     */
    private static String shortName(String id) {
        if (id == null) {
            return "";
        }
        if (PeerId.isValid(id)) {
            return PeerId.shortForm(id);
        }
        int at = id.indexOf('@');
        return at > 0 ? id.substring(0, at) : id;
    }

    @Override
    public Dimension getPreferredSize() {
        int available = getParent() != null ? getParent().getWidth() : 600;
        if (available <= 0) {
            available = 600;
        }
        layoutText(available);

        int height = PAD_V * 2 + replyBlockHeight + lines.size() * lineHeight;
        if (metaOnOwnLine) {
            height += getFontMetrics(Theme.bubbleMeta()).getHeight();
        }
        int width = textBlockWidth + PAD_H * 2 + TAIL_W;
        return new Dimension(width, Math.max(height, 32));
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    // ---------------------------------------------------------------- painting

    /**
     * Telegram's bubble outline: rounded on three corners, squared off where the tail meets the
     * body, with a small hook curving away from the bottom corner.
     */
    private Area bubbleShape(int w, int h) {
        // The tail gutter is reserved on every bubble, tailed or not, so the bodies of a grouped
        // run share one left edge and only the last bubble's hook reaches into the gutter. Sizing
        // the gutter per-bubble instead made grouped bubbles step in and out by 7px.
        int bodyX = outgoing ? 0 : TAIL_W;
        int bodyW = w - TAIL_W;

        Area area = new Area(new RoundRectangle2D.Double(bodyX, 0, bodyW, h, RADIUS * 2, RADIUS * 2));
        if (!tail) {
            return area;
        }

        GeneralPath hook = new GeneralPath();
        if (outgoing) {
            double x = bodyX + bodyW;
            hook.moveTo(x - RADIUS, h - RADIUS);
            hook.lineTo(x - RADIUS, h);
            hook.lineTo(x + TAIL_W, h);
            hook.curveTo(x + 1, h - 2, x, h - RADIUS * 0.55, x, h - RADIUS);
            hook.closePath();
        } else {
            double x = bodyX;
            hook.moveTo(x + RADIUS, h - RADIUS);
            hook.lineTo(x + RADIUS, h);
            hook.lineTo(x - TAIL_W, h);
            hook.curveTo(x - 1, h - 2, x, h - RADIUS * 0.55, x, h - RADIUS);
            hook.closePath();
        }
        area.add(new Area(hook));
        return area;
    }

    private Color bubbleColor() {
        if (message.isError()) {
            return Theme.bubbleError();
        }
        return outgoing ? Theme.bubbleOut() : Theme.bubbleIn();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        layoutText(getParent() != null ? getParent().getWidth() : w);

        Area shape = bubbleShape(w, h);

        // Telegram floats bubbles over the wallpaper with a barely-there drop shadow.
        g2.setColor(new Color(0, 0, 0, Theme.isDark() ? 40 : 18));
        g2.translate(0, 1);
        g2.fill(shape);
        g2.translate(0, -1);

        g2.setColor(bubbleColor());
        g2.fill(shape);

        if (hovered) {
            g2.setColor(Theme.isDark() ? new Color(255, 255, 255, 10) : new Color(0, 0, 0, 8));
            g2.fill(shape);
        }

        int contentX = (outgoing ? 0 : TAIL_W) + PAD_H;
        int y = PAD_V;

        if (message.hasReply()) {
            paintReplyQuote(g2, contentX, y);
            y += replyBlockHeight;
        }

        g2.setColor(message.isError() ? Theme.danger() : Theme.textPrimary());
        int textTop = y;
        for (TextLayout layout : lines) {
            y += Math.round(layout.getAscent());
            layout.draw(g2, contentX, y);
            y += Math.round(layout.getDescent() + layout.getLeading());
        }

        paintMeta(g2, contentX, textTop, h);
        g2.dispose();
    }

    private void paintReplyQuote(Graphics2D g2, int x, int y) {
        Color accent = outgoing && !Theme.isDark() ? new Color(0x4FAE4E) : Theme.accent();
        g2.setColor(accent);
        g2.fill(new Rectangle2D.Double(x, y, 2.5, replyBlockHeight - 3));

        g2.setFont(Theme.font(Font.BOLD, 12.5f));
        FontMetrics nameFm = g2.getFontMetrics();
        g2.drawString(shortName(message.getReplyToSender()), x + 8, y + nameFm.getAscent());

        g2.setFont(Theme.font(Font.PLAIN, 12.5f));
        FontMetrics previewFm = g2.getFontMetrics();
        g2.setColor(Theme.textSecondary());
        g2.drawString(clip(safe(message.getReplyToPreview()), 48),
                x + 8, y + nameFm.getHeight() + previewFm.getAscent());
    }

    /** Draws the timestamp and ticks, bottom-right, inside the bubble. */
    private void paintMeta(Graphics2D g2, int contentX, int textTop, int h) {
        g2.setFont(Theme.bubbleMeta());
        FontMetrics fm = g2.getFontMetrics();
        String time = metaText();
        Icon icon = statusIcon();

        int metaW = metaWidth(fm);
        int bodyRight = contentX + textBlockWidth;
        int metaX = bodyRight - metaW;
        int metaBaseline = h - PAD_V - fm.getDescent();

        Color metaColor = outgoing ? Theme.timeOut() : Theme.timeIn();
        g2.setColor(metaColor);
        g2.drawString(time, metaX, metaBaseline);

        if (icon != null) {
            Color tickColor = message.getStatus() == ChatMessage.Status.FAILED
                    ? Theme.danger()
                    : (message.getStatus() == ChatMessage.Status.READ ? Theme.tick() : metaColor);
            int iconX = metaX + fm.stringWidth(time) + 3;
            int iconY = metaBaseline - icon.getIconHeight() + fm.getDescent() - 1;
            TgIcons.tinted(icon, tickColor).paintIcon(this, g2, iconX, iconY);
        }
    }

    /** Bounds the transcript uses to decide whether the pointer is over this bubble. */
    public Rectangle bubbleBounds() {
        return new Rectangle(0, 0, getWidth(), getHeight());
    }
}
