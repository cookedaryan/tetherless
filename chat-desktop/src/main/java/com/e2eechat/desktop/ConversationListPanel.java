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
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
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
    /** Says whether what has been pasted into search can be started, or why it cannot. */
    private final JLabel searchHint = new JLabel(" ");
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
        menu.addActionListener(e -> openDrawer());

        IconButton themeToggle = new IconButton(
                () -> TgIcons.themeToggle(19, Theme.isDark()),
                "Toggle dark mode");
        themeToggle.addActionListener(e -> Theme.toggle());

        searchField.setBorder(BorderFactory.createEmptyBorder(0, 34, 0, 10));
        searchField.setOpaque(false);
        searchField.setFont(Theme.font(Font.PLAIN, 13.5f));
        searchField.setPreferredSize(new Dimension(10, 36));
        searchField.addActionListener(e -> startChatFromSearch());
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
        header.add(buildSearchHintPanel(), BorderLayout.SOUTH);
        return header;
    }

    /**
     * The single line beneath the search box that answers a pasted id.
     *
     * <p>There used to be a second text field here, revealed by a "New chat" command, so the
     * sidebar had two boxes that both took a peer id and only one of them was visible at a time.
     * Search does the job on its own now, and this row is only ever one line of text.
     *
     * <p>It starts invisible, and {@link BorderLayout} skips invisible children when it measures,
     * so the row costs nothing until there is something to say.
     */
    private JComponent buildSearchHintPanel() {
        searchHint.setFont(Theme.font(Font.PLAIN, 11f));
        searchHint.setForeground(Theme.textSecondary());
        searchHint.setVisible(false);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
        panel.add(searchHint, BorderLayout.CENTER);
        return panel;
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
        // Starting a chat means putting an id in the search box, so the compose button points at
        // the box rather than opening a second one beside it.
        button.addActionListener(e -> searchField.requestFocusInWindow());
        return button;
    }

    // ------------------------------------------------------------------ actions

    private void openDrawer() {
        openPanel(SidePanel.Side.LEFT, 320, null,
                panel -> new NavigationDrawer(client, new NavigationDrawer.Destinations() {
                    @Override
                    public void profile() {
                        openPanel(SidePanel.Side.LEFT, 380, "Profile",
                                p -> new ProfilePanel(client));
                    }

                    @Override
                    public void settings() {
                        openSettings();
                    }

                    @Override
                    public void relay() {
                        openPanel(SidePanel.Side.LEFT, 380, "Relay",
                                p -> new RelayPanel(client));
                    }

                    @Override
                    public void about() {
                        openPanel(SidePanel.Side.LEFT, 380, "About", p -> new AboutPanel());
                    }
                }));
    }

    /**
     * Routes through the window, which owns the one open panel. Opening a destination therefore
     * replaces the drawer rather than stacking a second sheet on top of it.
     */
    private void openPanel(SidePanel.Side side, int width, String title,
                           SidePanel.ContentFactory content) {
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (window instanceof ChatWindow) {
            ((ChatWindow) window).openSidePanel(side, width, title, content);
        }
    }

    private void openSettings() {
        openPanel(SidePanel.Side.LEFT, 380, "Settings", panel -> new SettingsPanel());
    }

    /** The canonical id for what was typed, or null when it cannot be used. */
    static String validateNewChatId(String entered, String ownId) {
        if (entered == null || entered.trim().isEmpty()) {
            return null;
        }
        String peerId = PeerId.parse(entered);
        if (peerId == null || peerId.equals(ownId)) {
            return null;
        }
        return peerId;
    }

    /** What to show beneath the field, or null while there is nothing to say. */
    static String newChatError(String entered, String ownId) {
        if (entered == null || entered.trim().isEmpty()) {
            return null;
        }
        String peerId = PeerId.parse(entered);
        if (peerId == null) {
            return "That is not a peer id. An id is 32 hex characters.";
        }
        if (peerId.equals(ownId)) {
            return "That is your own id.";
        }
        return null;
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
                        c.setVerified(client.getPeerDirectory().isVerified(c.getPeerId()));
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
        String raw = searchField.getText() == null ? "" : searchField.getText().trim();
        String query = raw.toLowerCase(Locale.ROOT);
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
        updateSearchHint(raw);
        repaint();
    }

    /**
     * Search doubles as the way a conversation is started: paste an id and press Enter.
     *
     * <p>Only says so once what is typed is actually a startable id, so the line does not sit
     * under the box arguing with someone who is searching their existing chats.
     */
    private void updateSearchHint(String raw) {
        boolean startable = model.isEmpty()
                && validateNewChatId(raw, client.getClientId()) != null;
        searchHint.setText(startable ? "Press Enter to start a chat with this id" : " ");
        searchHint.setForeground(Theme.textSecondary());
        searchHint.setVisible(startable);
        revalidate();
    }

    /**
     * Enter in the search box. A valid id opens the conversation; anything else says why, which is
     * the only moment feedback is wanted - typing it out character by character would mean arguing
     * with someone who is halfway through pasting.
     */
    private void startChatFromSearch() {
        String typed = searchField.getText();
        String peerId = validateNewChatId(typed, client.getClientId());
        if (peerId == null) {
            String message = newChatError(typed, client.getClientId());
            if (message != null) {
                searchHint.setText(message);
                searchHint.setForeground(Theme.danger());
                searchHint.setVisible(true);
                revalidate();
            }
            return;
        }
        searchField.setText("");
        openConversation(peerId);
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
        // The peer directory is the source of truth for verification status; re-derive it rather
        // than copying from the previous row, since this method builds a fresh Conversation.
        updated.setVerified(client.getPeerDirectory().isVerified(peerId));
        if (existing != null) {
            allConversations.remove(existing);
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

            g2.setColor(Theme.sidebarBg());
            g2.fillRect(0, 0, w, h);

            // The open conversation is a raised card inset from the list, not a full-bleed block of
            // colour. The shadow is what separates it from the surface rather than a border.
            if (selected || hovered) {
                int inset = 6;
                int cardH = h - 8;
                if (selected) {
                    g2.setColor(Theme.shadow());
                    g2.fill(new RoundRectangle2D.Double(inset, 6, w - inset * 2, cardH, 16, 16));
                }
                g2.setColor(selected ? Theme.sidebarSelected() : Theme.sidebarHover());
                g2.fill(new RoundRectangle2D.Double(inset, 4, w - inset * 2, cardH, 16, 16));
            }

            int avatarY = (h - AVATAR) / 2;
            Avatars.paint(g2, conversation.getPeerId(), conversation.getDisplayName(),
                    9, avatarY, AVATAR);

            Color primary = selected ? Theme.sidebarSelectedText() : Theme.textPrimary();
            Color secondary = Theme.textSecondary();

            int textX = 9 + AVATAR + 11;
            int rightEdge = w - 12;

            // Timestamp, right-aligned on the name's baseline.
            g2.setFont(Theme.font(Font.PLAIN, 12f));
            FontMetrics timeFm = g2.getFontMetrics();
            String time = formatTimestamp(conversation.getLastTimestamp());
            int timeW = timeFm.stringWidth(time);
            g2.setColor(secondary);
            g2.drawString(time, rightEdge - timeW, 26);

            // Name, truncated so it never runs under the timestamp (or the verified shield).
            g2.setFont(Theme.chatName());
            FontMetrics nameFm = g2.getFontMetrics();
            int shieldWidth = conversation.isVerified() ? 18 : 0;
            int nameMax = rightEdge - timeW - 8 - textX - shieldWidth;
            g2.setColor(primary);
            g2.drawString(ellipsize(conversation.getDisplayName(), nameFm, nameMax), textX, 26);
            if (conversation.isVerified()) {
                int nameW = nameFm.stringWidth(
                        ellipsize(conversation.getDisplayName(), nameFm, nameMax));
                // Tinted with `primary`, which is white on a selected row, so the shield stays
                // legible against the selection colour rather than vanishing into it.
                TgIcons.tinted(TgIcons.shield(13), primary)
                        .paintIcon(this, g2, textX + nameW + 5, 15);
            }

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
                g2.setColor(Theme.badge());
                g2.fill(new RoundRectangle2D.Double(pillX, pillY, pillW, pillH, pillH, pillH));
                g2.setColor(Theme.badgeText());
                g2.drawString(count, pillX + (pillW - textW) / 2,
                        pillY + (pillH - badgeFm.getHeight()) / 2 + badgeFm.getAscent());
                previewRight = pillX - 8;
            }

            // Outgoing preview carries the delivery tick, as it does in Telegram.
            int previewX = textX;
            if (conversation.isLastFromSelf() && !conversation.getLastMessage().isEmpty()) {
                Icon tick;
                switch (conversation.getLastStatus()) {
                    case PENDING:
                        tick = TgIcons.clock(13);
                        break;
                    case SENT:
                        tick = TgIcons.check(15);
                        break;
                    default:
                        tick = TgIcons.doubleCheck(15);
                        break;
                }
                TgIcons.tinted(tick, Theme.tick()).paintIcon(this, g2, previewX, 36);
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
