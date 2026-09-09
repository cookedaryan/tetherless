package com.e2eechat.desktop.ui;

import com.e2eechat.desktop.ChatMessage;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JMenuItem;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The message transcript: wallpaper, grouped bubbles, date separators, an unread rule, a typing
 * indicator and a jump-to-latest button.
 *
 * <p>Messages arriving close together from one sender are grouped the way Telegram groups them —
 * tight spacing, and only the final bubble of a run carries a tail. Appending a message demotes its
 * predecessor's tail in place rather than rebuilding the transcript, so incoming traffic does not
 * make the view flicker.
 */
public class TranscriptPanel extends JLayeredPane {

    /** Messages closer together than this from one sender are drawn as a single run. */
    private static final long GROUP_WINDOW_MS = 5 * 60 * 1000L;

    private final JPanel column = new JPanel();
    private final JScrollPane scroll;
    private final IconButton jumpToLatest;
    private final TypingIndicator typingIndicator = new TypingIndicator();
    private final String localClientId;

    private final List<ChatMessage> messages = new ArrayList<>();
    /** Row component for each message id, so a search hit can be scrolled to. */
    private final Map<String, Component> rowsById = new HashMap<>();
    private MessageBubble lastBubble;
    private ChatMessage lastMessage;
    private LocalDate lastDate;
    private Consumer<ChatMessage> onReply = m -> { };

    public TranscriptPanel(String localClientId) {
        this.localClientId = localClientId;

        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(BorderFactory.createEmptyBorder(12, 0, 8, 0));

        scroll = new JScrollPane(column);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        styleScrollBar(scroll.getVerticalScrollBar());

        jumpToLatest = new IconButton(() -> TgIcons.chevronDown(20), "Jump to latest") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, Theme.isDark() ? 60 : 26));
                g2.fill(new Ellipse2D.Double(1, 2, getWidth() - 2, getHeight() - 2));
                g2.setColor(Theme.composerBg());
                g2.fill(new Ellipse2D.Double(0, 0, getWidth() - 2, getHeight() - 2));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        jumpToLatest.setPreferredSize(new Dimension(42, 42));
        jumpToLatest.setSize(42, 42);
        jumpToLatest.setVisible(false);
        jumpToLatest.addActionListener(e -> scrollToBottom(true));

        add(scroll, JLayeredPane.DEFAULT_LAYER);
        add(jumpToLatest, JLayeredPane.PALETTE_LAYER);

        scroll.getVerticalScrollBar().addAdjustmentListener(e -> updateJumpButton());
        // Not addListener: the static list lives for the life of the process, and ChatWindow
        // builds a fresh transcript on every conversation switch - so every panel it replaced,
        // and the whole message list behind it, was retained forever and repainted on every
        // toggle. follow() drops the registration when the panel leaves the window.
        Theme.follow(this, this::repaintAll);
    }

    /** Registers the callback fired when the user picks "Reply" from a bubble's context menu. */
    public void setOnReply(Consumer<ChatMessage> onReply) {
        this.onReply = onReply == null ? m -> { } : onReply;
    }

    private void repaintAll() {
        // Every child paints straight from the Theme accessors, so a repaint is enough to adopt a
        // palette change; no component rebuild is needed.
        styleScrollBar(scroll.getVerticalScrollBar());
        repaint();
        column.repaint();
    }

    private static void styleScrollBar(JScrollBar bar) {
        bar.setOpaque(false);
        bar.setUnitIncrement(18);
        bar.setPreferredSize(new Dimension(8, 0));
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = Theme.isDark() ? new Color(255, 255, 255, 45) : new Color(0, 0, 0, 45);
                trackColor = new Color(0, 0, 0, 0);
            }

            @Override
            protected javax.swing.JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected javax.swing.JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            private javax.swing.JButton zeroButton() {
                javax.swing.JButton b = new javax.swing.JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, java.awt.Rectangle r) {
                // Telegram's scrollbar floats with no track.
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, java.awt.Rectangle r) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.isDark() ? new Color(255, 255, 255, 55) : new Color(0, 0, 0, 55));
                g2.fill(new RoundRectangle2D.Double(r.x + 2, r.y, r.width - 4, r.height, 6, 6));
                g2.dispose();
            }
        });
    }

    @Override
    public void doLayout() {
        scroll.setBounds(0, 0, getWidth(), getHeight());
        int size = jumpToLatest.getPreferredSize().width;
        jumpToLatest.setBounds(getWidth() - size - 18, getHeight() - size - 14, size, size);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        ChatWallpaper.paint(g2, getWidth(), getHeight());
        g2.dispose();
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        // Layers overlap (the jump button sits over the scroll pane), so Swing must not take the
        // non-overlapping fast path or the button leaves trails when the transcript scrolls.
        return false;
    }

    // ------------------------------------------------------------------ model

    /** Clears the transcript and loads {@code history} in chronological order. */
    public void setHistory(List<ChatMessage> history) {
        messages.clear();
        column.removeAll();
        rowsById.clear();
        lastBubble = null;
        lastMessage = null;
        lastDate = null;
        column.add(typingIndicator);
        typingIndicator.setVisible(false);
        for (ChatMessage m : history) {
            appendInternal(m);
        }
        column.revalidate();
        column.repaint();
        scrollToBottom(false);
    }

    public void append(ChatMessage message) {
        BubbleRow arrived = appendInternal(message);
        column.revalidate();
        column.repaint();
        arrived.playEntrance();
        if (isNearBottom()) {
            scrollToBottom(false);
        } else {
            updateJumpButton();
        }
    }

    private BubbleRow appendInternal(ChatMessage message) {
        LocalDate date = Instant.ofEpochMilli(message.getTimestamp())
                .atZone(ZoneId.systemDefault()).toLocalDate();

        boolean newDay = !date.equals(lastDate);
        if (newDay) {
            addRow(new DateSeparator(date));
            lastDate = date;
        }

        boolean grouped = !newDay
                && lastMessage != null
                && lastMessage.getSender().equals(message.getSender())
                && message.getTimestamp() - lastMessage.getTimestamp() < GROUP_WINDOW_MS;

        // Only the final bubble of a run keeps its tail.
        if (grouped && lastBubble != null) {
            lastBubble.setTail(false);
        }

        boolean outgoing = message.getSender().equals(localClientId);
        MessageBubble bubble = new MessageBubble(message, outgoing, true);
        attachInteractions(bubble);

        BubbleRow row = new BubbleRow(bubble, outgoing, grouped);
        addRow(row);

        messages.add(message);
        lastBubble = bubble;
        lastMessage = message;
        if (message.getMessageId() != null) {
            rowsById.put(message.getMessageId(), row);
        }
        return row;
    }

    private void addRow(Component row) {
        // Rows always insert above the typing indicator, which stays pinned at the foot.
        int index = Math.max(0, column.getComponentCount() - 1);
        if (column.getComponentCount() == 0) {
            column.add(typingIndicator);
            index = 0;
        }
        column.add(row, index);
    }

    /**
     * Marks the newest outgoing message with {@code messageId} as reaching {@code status}.
     *
     * <p>A tick only ever moves forward. {@code ChatClient} re-acknowledges on every read receipt
     * and the relay is expected to redeliver, so a {@code DELIVERY_ACK} can arrive after a read
     * receipt has already been applied - and setting it unconditionally pulled the bubble back
     * from a filled double tick to a plain one, which the sender reads as the peer un-reading
     * their message. {@code markAllRead} just below already guarded its own transition.
     */
    public void updateStatus(String messageId, ChatMessage.Status status) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (messageId.equals(m.getMessageId())) {
                if (isRegression(m.getStatus(), status)) {
                    return;
                }
                m.setStatus(status);
                repaint();
                return;
            }
        }
    }

    /**
     * Whether moving from {@code from} to {@code to} would walk back down the delivery ladder.
     *
     * <p>Only PENDING → SENT → DELIVERED → READ is ordered. FAILED is not on that ladder at all,
     * so nothing involving it is a regression: a message that failed and later goes out has to be
     * able to become SENT again, or it keeps a warning it has outgrown.
     */
    private static boolean isRegression(ChatMessage.Status from, ChatMessage.Status to) {
        int wasAt = ladderPosition(from);
        int goingTo = ladderPosition(to);
        return wasAt > 0 && goingTo > 0 && goingTo <= wasAt;
    }

    /** Position on the delivery ladder, or 0 for a status that is not on it. */
    private static int ladderPosition(ChatMessage.Status status) {
        switch (status) {
            case PENDING:
                return 1;
            case SENT:
                return 2;
            case DELIVERED:
                return 3;
            case READ:
                return 4;
            default:
                return 0;
        }
    }

    /** Advances every outgoing message at or below {@code DELIVERED} to {@code READ}. */
    public void markAllRead() {
        for (ChatMessage m : messages) {
            if (m.getSender().equals(localClientId)
                    && (m.getStatus() == ChatMessage.Status.SENT
                        || m.getStatus() == ChatMessage.Status.DELIVERED)) {
                m.setStatus(ChatMessage.Status.READ);
            }
        }
        repaint();
    }

    public void setTyping(boolean typing) {
        typingIndicator.setActive(typing);
        column.revalidate();
        column.repaint();
        if (typing && isNearBottom()) {
            scrollToBottom(false);
        }
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    // ------------------------------------------------------------ interaction

    private void attachInteractions(MessageBubble bubble) {
        bubble.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                bubble.setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                bubble.setHovered(false);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }

            private void maybePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    contextMenu(bubble).show(bubble, e.getX(), e.getY());
                }
            }
        });
    }

    private JPopupMenu contextMenu(MessageBubble bubble) {
        JPopupMenu menu = new JPopupMenu();
        ChatMessage m = bubble.getMessage();

        JMenuItem reply = new JMenuItem("Reply");
        reply.addActionListener(e -> onReply.accept(m));
        menu.add(reply);

        JMenuItem copy = new JMenuItem("Copy Text");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(m.getContent()), null));
        menu.add(copy);

        JMenuItem selectAll = new JMenuItem("Copy Selected Date");
        selectAll.setText("Copy Timestamp");
        selectAll.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(
                                Instant.ofEpochMilli(m.getTimestamp())
                                        .atZone(ZoneId.systemDefault()))), null));
        menu.add(selectAll);

        return menu;
    }

    // --------------------------------------------------------------- scrolling

    private boolean isNearBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 120;
    }

    private void updateJumpButton() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        boolean show = bar.getValue() + bar.getVisibleAmount() < bar.getMaximum() - 200;
        if (show != jumpToLatest.isVisible()) {
            jumpToLatest.setVisible(show);
            repaint();
        }
    }

    /** Scrolls to the newest message. Runs twice so the second pass sees the re-laid-out height. */
    public void scrollToBottom(boolean animate) {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
            SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
        });
    }

    /**
     * Brings the message with {@code messageId} into view.
     *
     * @return false when no row carries that id - messages stored before ids were recorded have
     *         none, and a caller should say so rather than appear to do nothing
     */
    public boolean scrollTo(String messageId) {
        if (messageId == null) {
            return false;
        }
        Component row = rowsById.get(messageId);
        if (row == null) {
            return false;
        }
        Rectangle bounds = row.getBounds();
        column.scrollRectToVisible(new Rectangle(0, Math.max(0, bounds.y - 40),
                bounds.width, bounds.height + 80));
        return true;
    }

    // -------------------------------------------------------------- sub-views

    /** Aligns one bubble to its side of the transcript with Telegram's margins. */
    private static class BubbleRow extends JPanel {
        /** 1 = settled. Below 1 the row is faded and offset, for a message that just arrived. */
        private float entrance = 1f;

        BubbleRow(MessageBubble bubble, boolean outgoing, boolean grouped) {
            setOpaque(false);
            setLayout(new BorderLayout());
            int top = grouped ? 2 : 8;
            setBorder(BorderFactory.createEmptyBorder(top, 16, 0, 16));
            JPanel holder = new JPanel(new java.awt.FlowLayout(
                    outgoing ? java.awt.FlowLayout.RIGHT : java.awt.FlowLayout.LEFT, 0, 0));
            holder.setOpaque(false);
            holder.add(bubble);
            add(holder, BorderLayout.CENTER);
        }

        /**
         * Fades in and rises the last few pixels.
         *
         * <p>Called only for a message that arrives while the transcript is open. Playing this for
         * every row when a conversation loads would be a hundred bubbles moving at once, which is
         * noise rather than polish - the history was always there, and pretending it just arrived
         * is a lie the animation tells.
         */
        void playEntrance() {
            entrance = 0f;
            Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, progress -> {
                entrance = progress;
                repaint();
            }, null);
        }

        @Override
        public void paint(Graphics g) {
            if (entrance >= 1f) {
                super.paint(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(0, Math.round((1f - entrance) * 10f));
                g2.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, Math.max(0f, entrance)));
                super.paint(g2);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** The "Today" / "Yesterday" / "14 March" rule across the transcript. */
    private static class DateSeparator extends JComponent {
        private final String label;

        DateSeparator(LocalDate date) {
            LocalDate today = LocalDate.now();
            if (date.equals(today)) {
                label = "Today";
            } else if (date.equals(today.minusDays(1))) {
                label = "Yesterday";
            } else if (date.getYear() == today.getYear()) {
                label = date.format(DateTimeFormatter.ofPattern("d MMMM"));
            } else {
                label = date.format(DateTimeFormatter.ofPattern("d MMMM yyyy"));
            }
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, 34);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 34);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // A centred label with a hairline running out to each margin, rather than a pill: on a
            // flat surface there is no wallpaper for a pill to float over, so it would just be a
            // lozenge sitting on the same colour it is drawn against.
            g2.setFont(Theme.font(Font.PLAIN, 12f));
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(label);
            int centreX = getWidth() / 2;
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            int lineY = getHeight() / 2;
            int gap = 14;
            int margin = 28;

            g2.setColor(Theme.divider());
            g2.drawLine(margin, lineY, centreX - textW / 2 - gap, lineY);
            g2.drawLine(centreX + textW / 2 + gap, lineY, getWidth() - margin, lineY);

            g2.setColor(Theme.textSecondary());
            g2.drawString(label, centreX - textW / 2f, baseline);
            g2.dispose();
        }
    }

    /** Telegram's three-dot "typing…" bubble, pinned to the foot of the transcript. */
    private static class TypingIndicator extends JComponent {
        private final Timer timer;
        private int phase;

        TypingIndicator() {
            setOpaque(false);
            setVisible(false);
            timer = new Timer(320, e -> {
                phase = (phase + 1) % 3;
                repaint();
            });
        }

        void setActive(boolean active) {
            setVisible(active);
            if (active) {
                if (!timer.isRunning()) {
                    timer.start();
                }
            } else {
                timer.stop();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return isVisible() ? new Dimension(70, 42) : new Dimension(0, 0);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, isVisible() ? 42 : 0);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!isVisible()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int bubbleW = 62;
            int bubbleH = 32;
            int x = 16;
            int y = 4;
            g2.setColor(new Color(0, 0, 0, Theme.isDark() ? 40 : 18));
            g2.fill(new RoundRectangle2D.Double(x, y + 1, bubbleW, bubbleH, 26, 26));
            g2.setColor(Theme.bubbleIn());
            g2.fill(new RoundRectangle2D.Double(x, y, bubbleW, bubbleH, 26, 26));

            for (int i = 0; i < 3; i++) {
                float alpha = (i == phase) ? 1f : 0.35f;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                g2.setColor(Theme.textSecondary());
                g2.fill(new Ellipse2D.Double(x + 15 + i * 11, y + bubbleH / 2.0 - 3, 6, 6));
            }
            g2.dispose();
        }
    }

    /** Empty-state placeholder shown before a conversation is picked. */
    public static JComponent emptyState(String text) {
        JComponent c = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ChatWallpaper.paint(g2, getWidth(), getHeight());
                g2.setFont(Theme.font(Font.PLAIN, 13.5f));
                FontMetrics fm = g2.getFontMetrics();
                int textW = fm.stringWidth(text);
                int pillW = textW + 32;
                int pillH = 30;
                int x = (getWidth() - pillW) / 2;
                int y = (getHeight() - pillH) / 2;
                g2.setColor(Theme.floatingPill());
                g2.fill(new RoundRectangle2D.Double(x, y, pillW, pillH, pillH, pillH));
                g2.setColor(Theme.floatingPillText());
                g2.drawString(text, x + 16, y + (pillH - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
        c.setOpaque(false);
        return c;
    }
}
