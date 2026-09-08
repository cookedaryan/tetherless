package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.desktop.ui.Avatars;
import com.e2eechat.desktop.ui.EmojiText;
import com.e2eechat.desktop.ui.IconButton;
import com.e2eechat.desktop.ui.SidePanel;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Telegram's chat list: search bar, conversation rows with avatar, preview, timestamp and unread
 * badge, and a floating compose button.
 *
 * <p>Rows are painted end to end rather than composed from labels. A {@code GridLayout} of labels
 * cannot put the timestamp and the unread pill on their own baselines at the right edge while the
 * name and preview flow from the left, which is what the real row looks like.
 */
public class ConversationListPanel extends JLayeredPane {

    private static final int ROW_HEIGHT = 68;
    private static final int AVATAR = 54;

    private final ChatClient client;
    private final Consumer<String> onConversationSelected;

    private final DefaultListModel<Conversation> model = new DefaultListModel<>();
    private final JList<Conversation> list = new JList<>(model);
    private final JTextField searchField = new JTextField();
    private final IconButton composeButton;
    private final JPanel root = new JPanel(new BorderLayout());

    /** Full, unfiltered set; {@link #model} holds whatever the current search leaves visible. */
    private final List<Conversation> allConversations = new ArrayList<>();

    public ConversationListPanel(ChatClient client, Consumer<String> onConversationSelected) {
        this.client = client;
        this.onConversationSelected = onConversationSelected;

        setPreferredSize(new Dimension(320, 0));

        root.setBackground(Theme.sidebarBg());
        root.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.divider()));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildList(), BorderLayout.CENTER);

        composeButton = buildComposeButton();

        add(root, JLayeredPane.DEFAULT_LAYER);
        add(composeButton, JLayeredPane.PALETTE_LAYER);

        Theme.addListener(this::applyTheme);
        reload();
    }

    // ----------------------------------------------------------------- layout

    @Override
    public void doLayout() {
        root.setBounds(0, 0, getWidth(), getHeight());
        int size = 56;
        composeButton.setBounds(getWidth() - size - 20, getHeight() - size - 20, size, size);
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        // The compose button overlaps the list, so Swing must repaint both layers together.
        return false;
    }

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(Theme.headerBg());
        header.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));

        IconButton menu = new IconButton(() -> TgIcons.menu(20), "Menu");
        menu.addActionListener(e -> showMenu(menu));

        IconButton themeToggle = new IconButton(
                () -> TgIcons.themeToggle(19, Theme.isDark()),
                "Toggle dark mode");
        themeToggle.addActionListener(e -> Theme.toggle());

        searchField.setBorder(BorderFactory.createEmptyBorder(0, 34, 0, 10));
        searchField.setOpaque(false);
        searchField.setFont(Theme.font(Font.PLAIN, 13.5f));
        searchField.setPreferredSize(new Dimension(10, 36));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        JPanel searchWrap = new SearchField(searchField);
        searchWrap.setLayout(new BorderLayout());
        searchWrap.add(searchField, BorderLayout.CENTER);

        // The placeholder is painted by the wrapper, so it has to repaint when focus moves.
        searchField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                searchWrap.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                searchWrap.repaint();
            }
        });

        JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        trailing.setOpaque(false);
        trailing.add(themeToggle);

        header.add(menu, BorderLayout.WEST);
        header.add(searchWrap, BorderLayout.CENTER);
        header.add(trailing, BorderLayout.EAST);
        return header;
    }

    private JComponent buildList() {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ConversationRenderer());
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setBorder(null);
        list.setBackground(Theme.sidebarBg());

        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Conversation selected = list.getSelectedValue();
                if (selected != null) {
                    selected.setUnreadCount(0);
                    onConversationSelected.accept(selected.getPeerId());
                    list.repaint();
                }
            }
        });

        // Hover highlight, which a plain JList does not provide.
        list.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index != hoverIndex) {
                    hoverIndex = index;
                    list.repaint();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                hoverIndex = -1;
                list.repaint();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(Theme.sidebarBg());
        return scroll;
    }

    private int hoverIndex = -1;

    private IconButton buildComposeButton() {
        IconButton button = new IconButton(() -> TgIcons.pencil(22), "New chat",
                () -> Color.WHITE, () -> Color.WHITE) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0, 0, 0, 45));
                g2.fill(new Ellipse2D.Double(2, 4, getWidth() - 4, getHeight() - 4));
                g2.setPaint(new java.awt.GradientPaint(
                        0, 0, Theme.accent(), 0, getHeight(), Theme.accentHover()));
                g2.fill(new Ellipse2D.Double(1, 1, getWidth() - 4, getHeight() - 4));
                g2.dispose();
                Icon icon = TgIcons.pencil(22);
                icon.paintIcon(this, g, (getWidth() - icon.getIconWidth()) / 2 - 1,
                        (getHeight() - icon.getIconHeight()) / 2 - 1);
            }
        }.withoutHoverCircle();
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> promptNewChat());
        return button;
    }

    // ------------------------------------------------------------------ actions

    private void showMenu(Component anchor) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        javax.swing.JMenuItem newChat = new javax.swing.JMenuItem("New chat…");
        newChat.addActionListener(e -> promptNewChat());
        menu.add(newChat);

        menu.addSeparator();

        // Identity, appearance and version all moved into the settings sheet, which has the room to
        // explain them. Night mode stays here too: it is the one setting people flip often enough
        // to want without opening anything.
        javax.swing.JMenuItem settings = new javax.swing.JMenuItem("Settings");
        settings.addActionListener(e -> openSettings());
        menu.add(settings);

        javax.swing.JCheckBoxMenuItem night = new javax.swing.JCheckBoxMenuItem("Night mode");
        night.setSelected(Theme.isDark());
        night.addActionListener(e -> Theme.toggle());
        menu.add(night);

        menu.show(anchor, 0, anchor.getHeight());
    }

    private void openSettings() {
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (window instanceof java.awt.Frame) {
            SidePanel.open((java.awt.Frame) window, SidePanel.Side.LEFT, 420, "Settings",
                panel -> new SettingsPanel(client));
        }
    }

    private void promptNewChat() {
        String entered = JOptionPane.showInputDialog(this,
                "Enter the peer's id (32 characters, e.g. 4f3a91c2-8b7e05d6-...):",
                "New chat", JOptionPane.PLAIN_MESSAGE);
        if (entered == null || entered.trim().isEmpty()) {
            return;
        }
        // Accept whatever form the user pasted - grouped, spaced, or upper case - but store the
        // canonical id, since routing compares it byte for byte.
        String peerId = PeerId.parse(entered);
        if (peerId == null) {
            JOptionPane.showMessageDialog(this,
                    "That is not a peer id.\n\nAn id is 32 hex characters derived from the peer's\n"
                            + "identity key. Ask them for it under Menu > My identity.",
                    "New chat", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (peerId.equals(client.getClientId())) {
            JOptionPane.showMessageDialog(this, "That is your own id.",
                    "New chat", JOptionPane.WARNING_MESSAGE);
            return;
        }
        openConversation(peerId);
    }

    /** Selects an existing conversation or inserts a placeholder row for a brand-new peer. */
    public void openConversation(String peerId) {
        for (int i = 0; i < allConversations.size(); i++) {
            if (allConversations.get(i).getPeerId().equals(peerId)) {
                searchField.setText("");
                applyFilter();
                list.setSelectedIndex(indexOfPeer(peerId));
                return;
            }
        }
        Conversation fresh = new Conversation(peerId, "", System.currentTimeMillis(),
                false, ChatMessage.Status.SENT, 0);
        fresh.setDisplayName(client.displayNameFor(peerId));
        allConversations.add(0, fresh);
        searchField.setText("");
        applyFilter();
        list.setSelectedIndex(indexOfPeer(peerId));
    }

    private int indexOfPeer(String peerId) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).getPeerId().equals(peerId)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------- data

    /** Reloads every conversation from the store, preserving the current selection. */
    public void reload() {
        String selectedPeer = list.getSelectedValue() != null
                ? list.getSelectedValue().getPeerId()
                : null;

        new SwingWorker<List<Conversation>, Void>() {
            @Override
            protected List<Conversation> doInBackground() {
                return client.getMessageRepository().getConversations(client.getClientId());
            }

            @Override
            protected void done() {
                try {
                    List<Conversation> loaded = get();
                    // Names live in the peer directory now, not in the id, so resolve them here.
                    for (Conversation c : loaded) {
                        c.setDisplayName(client.displayNameFor(c.getPeerId()));
                    }
                    // Keep any placeholder rows for peers with no messages yet.
                    for (Conversation existing : allConversations) {
                        boolean known = loaded.stream()
                                .anyMatch(c -> c.getPeerId().equals(existing.getPeerId()));
                        if (!known) {
                            loaded.add(existing);
                        }
                    }
                    allConversations.clear();
                    allConversations.addAll(loaded);
                    applyFilter();
                    if (selectedPeer != null) {
                        int index = indexOfPeer(selectedPeer);
                        if (index >= 0) {
                            list.setSelectedIndex(index);
                        }
                    }
                } catch (Exception ignored) {
                    // A failed reload leaves the previous list on screen, which is the safe outcome.
                }
            }
        }.execute();
    }

    private void applyFilter() {
        String query = searchField.getText() == null
                ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        model.clear();
        for (Conversation c : allConversations) {
            if (query.isEmpty()
                    || c.getDisplayName().toLowerCase(Locale.ROOT).contains(query)
                    || c.getPeerId().toLowerCase(Locale.ROOT).contains(query)
                    || (c.getLastMessage() != null
                        && c.getLastMessage().toLowerCase(Locale.ROOT).contains(query))) {
                model.addElement(c);
            }
        }
        repaint();
    }

    /** Bumps a peer to the top with a new preview, without a full round trip to the database. */
    public void notePreview(String peerId, String preview, long timestamp,
                            boolean fromSelf, boolean incrementUnread) {
        Conversation existing = null;
        for (Conversation c : allConversations) {
            if (c.getPeerId().equals(peerId)) {
                existing = c;
                break;
            }
        }
        int unread = existing == null ? 0 : existing.getUnreadCount();
        if (incrementUnread) {
            unread++;
        }
        Conversation updated = new Conversation(peerId, preview, timestamp, fromSelf,
                ChatMessage.Status.SENT, unread);
        updated.setDisplayName(client.displayNameFor(peerId));
        if (existing != null) {
            allConversations.remove(existing);
            updated.setVerified(existing.isVerified());
        }
        allConversations.add(0, updated);
        String selectedPeer = list.getSelectedValue() != null
                ? list.getSelectedValue().getPeerId() : null;
        applyFilter();
        if (selectedPeer != null) {
            int index = indexOfPeer(selectedPeer);
            if (index >= 0) {
                list.setSelectedIndex(index);
            }
        }
    }

    public void clearUnread(String peerId) {
        for (Conversation c : allConversations) {
            if (c.getPeerId().equals(peerId)) {
                c.setUnreadCount(0);
            }
        }
        repaint();
    }

    private void applyTheme() {
        root.setBackground(Theme.sidebarBg());
        root.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.divider()));
        list.setBackground(Theme.sidebarBg());
        searchField.setForeground(Theme.textPrimary());
        searchField.setCaretColor(Theme.accent());
        for (Component c : root.getComponents()) {
            c.setBackground(Theme.headerBg());
        }
        repaint();
    }

    // -------------------------------------------------------------- sub-views

    /** Rounded pill behind the search input, with the magnifier and placeholder drawn inside it. */
    private static class SearchField extends JPanel {

        private final JTextField field;

        SearchField(JTextField field) {
            this.field = field;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.inputBg());
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(),
                    getHeight(), getHeight()));
            Icon search = TgIcons.search(16);
            TgIcons.tinted(search, Theme.icon())
                    .paintIcon(this, g2, 11, (getHeight() - search.getIconHeight()) / 2);

            // Placeholder, drawn here rather than seeded into the field so the user never has to
            // clear literal "Search" text before typing.
            if (field.getText().isEmpty() && !field.hasFocus()) {
                g2.setFont(Theme.font(Font.PLAIN, 13.5f));
                g2.setColor(Theme.textSecondary());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("Search", 34,
                        (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.dispose();
        }
    }

    /** Paints one chat-list row. */
    private class ConversationRenderer extends JComponent
            implements ListCellRenderer<Conversation> {

        private Conversation conversation;
        private boolean selected;
        private boolean hovered;

        @Override
        public Component getListCellRendererComponent(JList<? extends Conversation> list,
                                                      Conversation value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.conversation = value;
            this.selected = isSelected;
            this.hovered = index == hoverIndex && !isSelected;
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, ROW_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (conversation == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = ROW_HEIGHT;

            g2.setColor(selected ? Theme.sidebarSelected()
                    : hovered ? Theme.sidebarHover() : Theme.sidebarBg());
            g2.fillRect(0, 0, w, h);

            int avatarY = (h - AVATAR) / 2;
            Avatars.paint(g2, conversation.getPeerId(), conversation.getDisplayName(),
                    9, avatarY, AVATAR);

            Color primary = selected ? Color.WHITE : Theme.textPrimary();
            Color secondary = selected
                    ? new Color(255, 255, 255, 205) : Theme.textSecondary();

            int textX = 9 + AVATAR + 11;
            int rightEdge = w - 12;

            // Timestamp, right-aligned on the name's baseline.
            g2.setFont(Theme.font(Font.PLAIN, 12f));
            FontMetrics timeFm = g2.getFontMetrics();
            String time = formatTimestamp(conversation.getLastTimestamp());
            int timeW = timeFm.stringWidth(time);
            g2.setColor(secondary);
            g2.drawString(time, rightEdge - timeW, 26);

            // Name, truncated so it never runs under the timestamp.
            g2.setFont(Theme.chatName());
            FontMetrics nameFm = g2.getFontMetrics();
            int nameMax = rightEdge - timeW - 8 - textX;
            g2.setColor(primary);
            g2.drawString(ellipsize(conversation.getDisplayName(), nameFm, nameMax), textX, 26);

            // Unread pill, right-aligned on the preview's baseline.
            int previewRight = rightEdge;
            if (conversation.getUnreadCount() > 0) {
                String count = conversation.getUnreadCount() > 99
                        ? "99+" : String.valueOf(conversation.getUnreadCount());
                g2.setFont(Theme.font(Font.BOLD, 11.5f));
                FontMetrics badgeFm = g2.getFontMetrics();
                int textW = badgeFm.stringWidth(count);
                int pillW = Math.max(21, textW + 14);
                int pillH = 21;
                int pillX = rightEdge - pillW;
                int pillY = 33;
                g2.setColor(selected ? Color.WHITE : Theme.badge());
                g2.fill(new RoundRectangle2D.Double(pillX, pillY, pillW, pillH, pillH, pillH));
                g2.setColor(selected ? Theme.sidebarSelected() : Theme.badgeText());
                g2.drawString(count, pillX + (pillW - textW) / 2,
                        pillY + (pillH - badgeFm.getHeight()) / 2 + badgeFm.getAscent());
                previewRight = pillX - 8;
            }

            // Outgoing preview carries the delivery tick, as it does in Telegram.
            int previewX = textX;
            if (conversation.isLastFromSelf() && !conversation.getLastMessage().isEmpty()) {
                Icon tick = conversation.getLastStatus() == ChatMessage.Status.SENT
                        ? TgIcons.check(15) : TgIcons.doubleCheck(15);
                TgIcons.tinted(tick, selected ? Color.WHITE : Theme.tick())
                        .paintIcon(this, g2, previewX, 36);
                previewX += tick.getIconWidth() + 4;
            }

            g2.setFont(Theme.chatPreview());
            FontMetrics previewFm = g2.getFontMetrics();
            String preview = conversation.getLastMessage() == null
                    || conversation.getLastMessage().isEmpty()
                    ? "No messages yet"
                    : conversation.getLastMessage().replace('\n', ' ');
            g2.setColor(secondary);
            // Drawn through EmojiText so a preview ending in an emoji is not a row of tofu boxes.
            EmojiText.draw(g2, ellipsize(preview, previewFm, previewRight - previewX),
                    Theme.chatPreview(), previewX, 49);

            g2.setColor(Theme.divider());
            g2.drawLine(textX, h - 1, w, h - 1);

            g2.dispose();
        }
    }

    private static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (maxWidth <= 0 || text == null) {
            return "";
        }
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    /** Telegram's chat-list clock: time today, weekday this week, otherwise a date. */
    private static String formatTimestamp(long epochMillis) {
        if (epochMillis <= 0) {
            return "";
        }
        ZonedDateTime zoned = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault());
        LocalDate date = zoned.toLocalDate();
        LocalDate today = LocalDate.now();

        if (date.equals(today)) {
            return zoned.format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        if (date.isAfter(today.minusDays(7))) {
            return zoned.format(DateTimeFormatter.ofPattern("EEE"));
        }
        if (date.getYear() == today.getYear()) {
            return zoned.format(DateTimeFormatter.ofPattern("dd MMM"));
        }
        return zoned.format(DateTimeFormatter.ofPattern("dd.MM.yy"));
    }
}
