package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.network.SessionStateListener;
import com.e2eechat.core.session.Session;
import com.e2eechat.desktop.ui.Avatars;
import com.e2eechat.desktop.ui.Composer;
import com.e2eechat.desktop.ui.IconButton;
import com.e2eechat.desktop.ui.SidePanel;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.TranscriptPanel;
import com.e2eechat.desktop.ui.UpdateBanner;
import com.e2eechat.desktop.ui.WelcomePane;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The main Telegram-style window: chat list on the left, transcript and composer on the right.
 *
 * <p>Assembles {@link ConversationListPanel}, {@link TranscriptPanel} and {@link Composer}, and
 * translates protocol events into UI state. Composing stays disabled until the session for the open
 * conversation reaches {@link Session.State#ESTABLISHED} — there is deliberately no plaintext
 * fallback, so a failed handshake means no send rather than an unencrypted send.
 */
public class ChatWindow extends JFrame implements MessageListener, SessionStateListener {

    /** A typing notice from a peer expires if they go quiet, matching Telegram's own timeout. */
    private static final int TYPING_EXPIRY_MS = 6000;

    private final ChatClient client;
    private final String ownFingerprint;

    private final ConversationListPanel sidebar;
    private final ChatHeader header;
    private final JPanel rightPanel;
    private final Composer composer;

    private final UpdateBanner updateBanner = new UpdateBanner();

    private TranscriptPanel transcript;
    private JComponent emptyState;
    private final Timer typingExpiry;

    /** The panel currently open over the window, if any. Only one at a time. */
    private SidePanel openPanel;

    public ChatWindow(ChatClient client, String fingerprint) {
        this.client = client;
        this.ownFingerprint = fingerprint;

        setTitle("Tetherless");
        setSize(1080, 720);
        setMinimumSize(new Dimension(760, 520));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        sidebar = new ConversationListPanel(client, this::onConversationSelected);
        header = new ChatHeader();
        composer = new Composer();

        composer.setOnSend(this::onSend);
        composer.setOnTypingChanged(this::onLocalTypingChanged);

        emptyState = new WelcomePane("Welcome to Tetherless",
                "Conversations start from a peer id. Share yours from Menu, or open a chat "
                        + "with someone else's.",
                true);

        rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(header, BorderLayout.NORTH);
        rightPanel.add(emptyState, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, rightPanel);
        split.setDividerSize(0);
        split.setBorder(null);
        split.setResizeWeight(0);

        // The frame's own BorderLayout, so the banner spans the sidebar and the chat both. It is
        // hidden until there is something to announce, so this costs a row of nothing.
        add(updateBanner, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        header.setComposerVisible(false);

        typingExpiry = new Timer(TYPING_EXPIRY_MS, e -> {
            if (transcript != null) {
                transcript.setTyping(false);
            }
        });
        typingExpiry.setRepeats(false);

        Theme.addListener(this::applyTheme);
        applyTheme();

        client.addMessageListener(this);

        // Fire and forget. The check runs off the event thread and stays silent unless there is a
        // newer release, so nothing here can delay the window appearing.
        new UpdateChecker().checkInBackground(
                update -> updateBanner.show(update.version(), update.url()));
    }

    private void applyTheme() {
        rightPanel.setBackground(Theme.chatBg());
        getContentPane().setBackground(Theme.chatBg());
        repaint();
    }

    // -------------------------------------------------------- conversation switch

    private void onConversationSelected(String peerId) {
        header.setPeer(peerId);
        header.setStatus("connecting…", Theme.textSecondary(), false);
        header.setComposerVisible(true);
        composer.setComposingEnabled(false);
        composer.clearReply();

        TranscriptPanel fresh = new TranscriptPanel(client.getClientId());
        fresh.setOnReply(this::onReplyRequested);
        swapCentre(fresh);
        transcript = fresh;

        client.startSecureChat(peerId);

        new SwingWorker<List<ChatMessage>, Void>() {
            @Override
            protected List<ChatMessage> doInBackground() {
                List<ChatMessage> history = client.getMessageRepository()
                        .getMessages(client.getClientId(), peerId, 200);
                client.getMessageRepository().markConversationRead(client.getClientId(), peerId);
                return history;
            }

            @Override
            protected void done() {
                try {
                    if (transcript != fresh) {
                        return; // The user moved on before the history arrived.
                    }
                    fresh.setHistory(get());
                    sidebar.clearUnread(peerId);
                    // Tell the peer their messages have been seen.
                    client.sendReadReceipt(peerId);
                } catch (Exception ignored) {
                    // History is best-effort; an empty transcript is preferable to a crash.
                }
            }
        }.execute();

        composer.focusInput();
    }

    private void swapCentre(JComponent centre) {
        if (emptyState != null) {
            rightPanel.remove(emptyState);
            emptyState = null;
        }
        BorderLayout layout = (BorderLayout) rightPanel.getLayout();
        java.awt.Component existing = layout.getLayoutComponent(BorderLayout.CENTER);
        if (existing != null) {
            rightPanel.remove(existing);
        }
        java.awt.Component south = layout.getLayoutComponent(BorderLayout.SOUTH);
        if (south == null) {
            rightPanel.add(composer, BorderLayout.SOUTH);
        }
        rightPanel.add(centre, BorderLayout.CENTER);
        rightPanel.revalidate();
        rightPanel.repaint();
    }

    // ------------------------------------------------------------------ sending

    private void onSend(String text) {
        String peerId = client.getReceiverId();
        if (peerId == null || transcript == null) {
            return;
        }
        ChatMessage replyTo = composer.getReplyTarget();
        String messageId = client.sendMessage(text, replyTo);
        if (messageId == null) {
            // Nothing went out. Put the text back rather than letting it vanish - the header says
            // why, and the user should not have to retype what they just wrote.
            composer.restoreDraft(text);
            return;
        }

        ChatMessage sent = new ChatMessage(
                messageId, client.getClientId(), peerId, text, System.currentTimeMillis(),
                ChatMessage.Status.SENT,
                replyTo == null ? null : replyTo.getMessageId(),
                replyTo == null ? null : displayNameOf(replyTo.getSender()),
                replyTo == null ? null : replyTo.getContent());

        transcript.append(sent);
        sidebar.notePreview(peerId, text, sent.getTimestamp(), true, false);
    }

    private void onReplyRequested(ChatMessage target) {
        composer.setReplyTarget(target, displayNameOf(target.getSender()));
    }

    private void onLocalTypingChanged(boolean typing) {
        String peerId = client.getReceiverId();
        if (peerId != null) {
            client.sendTyping(peerId, typing);
        }
    }

    /**
     * The label for a peer. Names are metadata a peer asserts about themselves, so this resolves
     * through the directory and falls back to a short form of their id when none is known.
     */
    private String displayNameOf(String id) {
        return client.displayNameFor(id);
    }

    // ---------------------------------------------------------- protocol events

    @Override
    public void onMessageReceived(Message msg) {
        SwingUtilities.invokeLater(() -> handleMessage(msg));
    }

    private void handleMessage(Message msg) {
        switch (msg.getType()) {
            case ERROR:
                header.setStatus(new String(msg.getPayload(), StandardCharsets.UTF_8),
                        Theme.danger(), false);
                composer.setComposingEnabled(false);
                return;

            case TYPING:
                if (isCurrentPeer(msg.getSenderId()) && transcript != null) {
                    boolean typing = msg.getPayload() != null
                            && msg.getPayload().length > 0
                            && msg.getPayload()[0] == 1;
                    transcript.setTyping(typing);
                    if (typing) {
                        typingExpiry.restart();
                    } else {
                        typingExpiry.stop();
                    }
                }
                return;

            case READ_RECEIPT:
                if (isCurrentPeer(msg.getSenderId()) && transcript != null) {
                    transcript.markAllRead();
                }
                return;

            case DELIVERY_ACK:
                if (transcript != null) {
                    String ackedId = new String(msg.getPayload(), StandardCharsets.UTF_8);
                    transcript.updateStatus(ackedId, ChatMessage.Status.DELIVERED);
                }
                return;

            case TEXT_MESSAGE:
                handleIncomingText(msg);
                return;

            default:
                // Handshake traffic is handled in ChatClient; nothing to render here.
        }
    }

    private void handleIncomingText(Message msg) {
        String text = new String(msg.getPayload(), StandardCharsets.UTF_8);
        boolean current = isCurrentPeer(msg.getSenderId());

        if (current && transcript != null) {
            typingExpiry.stop();
            transcript.setTyping(false);
            transcript.append(new ChatMessage(
                    msg.getMessageId(), msg.getSenderId(), client.getClientId(),
                    text, msg.getTimestamp(), ChatMessage.Status.DELIVERED));
            client.getMessageRepository()
                    .markConversationRead(client.getClientId(), msg.getSenderId());
            client.sendReadReceipt(msg.getSenderId());
        }

        sidebar.notePreview(msg.getSenderId(), text, msg.getTimestamp(), false, !current);
        if (!current) {
            // Audible cue for a chat the user is not currently looking at.
            java.awt.Toolkit.getDefaultToolkit().beep();
        }
    }

    private boolean isCurrentPeer(String senderId) {
        return senderId != null && senderId.equals(client.getReceiverId());
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state) {
        SwingUtilities.invokeLater(() -> {
            String peerId = client.getReceiverId();
            if (state != ConnectionState.CONNECTED) {
                header.setStatus("connecting…", Theme.danger(), false);
                composer.setComposingEnabled(false);
                return;
            }
            if (peerId == null) {
                header.setStatus("connected", Theme.textSecondary(), false);
                return;
            }
            // The window opens before connect() finishes, and a reconnect drops the old session,
            // so the handshake has to be (re)driven whenever the transport comes up. Without this
            // a chat opened during startup stays permanently un-sendable.
            header.setStatus("establishing encryption…", Theme.textSecondary(), false);
            client.startSecureChat(peerId);
        });
    }

    @Override
    public void onSessionStateChanged(Session.State state) {
        SwingUtilities.invokeLater(() -> {
            String peerId = client.getReceiverId();
            if (peerId == null) {
                return;
            }
            if (state == Session.State.ESTABLISHED) {
                header.setStatus("end-to-end encrypted", Theme.accent(), true);
                composer.setComposingEnabled(true);
                composer.focusInput();
            } else {
                header.setStatus("establishing encryption…", Theme.textSecondary(), false);
                composer.setComposingEnabled(false);
            }
        });
    }

    // ------------------------------------------------------------------- header

    /** The bar above the transcript: avatar, peer name, session status, and chat actions. */
    private class ChatHeader extends JPanel {
        private String peerId;
        private String statusText = "";
        private Color statusColor = Theme.textSecondary();
        private boolean secure;

        private final JPanel actions = new JPanel();

        ChatHeader() {
            setLayout(new BorderLayout());
            setPreferredSize(new Dimension(10, 60));
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.divider()));

            actions.setOpaque(false);
            actions.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 12));

            IconButton safety = new IconButton(() -> TgIcons.shield(20), "Chat info");
            safety.addActionListener(e -> showChatInfo());

            IconButton search = new IconButton(() -> TgIcons.search(19), "Search in chat");
            search.addActionListener(e -> showChatSearch());

            IconButton more = new IconButton(() -> TgIcons.more(19), "More");
            more.addActionListener(e -> showChatMenu(more));

            actions.add(safety);
            actions.add(search);
            actions.add(more);
            add(actions, BorderLayout.EAST);
        }

        void setPeer(String peerId) {
            this.peerId = peerId;
            repaint();
        }

        void setStatus(String text, Color color, boolean secure) {
            this.statusText = text;
            this.statusColor = color;
            this.secure = secure;
            repaint();
        }

        void setComposerVisible(boolean visible) {
            actions.setVisible(visible);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(Theme.headerBg());
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (peerId == null) {
                g2.setFont(Theme.headerTitle());
                g2.setColor(Theme.textSecondary());
                g2.drawString("Tetherless", 20, 36);
                g2.dispose();
                return;
            }

            int avatar = 40;
            int avatarY = (getHeight() - avatar) / 2;
            Avatars.paint(g2, peerId, displayNameOf(peerId), 16, avatarY, avatar);

            int textX = 16 + avatar + 12;
            g2.setFont(Theme.headerTitle());
            g2.setColor(Theme.textPrimary());
            g2.drawString(displayNameOf(peerId), textX, 27);

            int statusX = textX;
            if (secure) {
                Icon lock = TgIcons.lock(12);
                TgIcons.tinted(lock, statusColor).paintIcon(this, g2, statusX, 33);
                statusX += lock.getIconWidth() + 5;
            }
            g2.setFont(Theme.headerSubtitle());
            g2.setColor(statusColor);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(statusText, statusX, 33 + fm.getAscent() - 1);

            g2.dispose();
        }
    }

    // ------------------------------------------------------------ header actions

    private void openPanel(SidePanel.Side side, String title, SidePanel.ContentFactory content) {
        openSidePanel(side, 380, title, content);
    }

    /**
     * Opens a panel over this window, closing whatever was open before.
     *
     * <p>Public because the sidebar opens Settings and needs the same owner: a panel opened
     * around this pair is not recorded, and the next open then fails to close it, leaving two
     * panels and two scrims stacked over the window.
     */
    public void openSidePanel(SidePanel.Side side, int width, String title,
                              SidePanel.ContentFactory content) {
        closePanel();
        openPanel = SidePanel.open(this, side, width, title, content);
    }

    private void closePanel() {
        if (openPanel != null) {
            openPanel.dismiss();
            openPanel = null;
        }
    }

    private void showChatInfo() {
        String peerId = client.getReceiverId();
        if (peerId == null) {
            return;
        }
        openPanel(SidePanel.Side.RIGHT, "Chat info",
            panel -> new ChatInfoPanel(client, peerId, ownFingerprint, () -> {
                closePanel();
                showChatSearch();
            }));
    }

    private void showChatSearch() {
        String peerId = client.getReceiverId();
        if (peerId == null) {
            return;
        }
        openPanel(SidePanel.Side.RIGHT, "Search",
            panel -> new SearchPanel(client, peerId, hit -> {
                if (transcript != null) {
                    transcript.scrollTo(hit.getMessageId());
                }
            }));
    }

    private void showChatMenu(java.awt.Component anchor) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        javax.swing.JMenuItem safety = new javax.swing.JMenuItem("Safety number…");
        safety.addActionListener(e -> showChatInfo());
        menu.add(safety);

        javax.swing.JMenuItem search = new javax.swing.JMenuItem("Search in chat…");
        search.addActionListener(e -> showChatSearch());
        menu.add(search);

        menu.addSeparator();

        javax.swing.JMenuItem rekey = new javax.swing.JMenuItem("Renegotiate encryption");
        rekey.addActionListener(e -> {
            String peerId = client.getReceiverId();
            if (peerId != null) {
                composer.setComposingEnabled(false);
                header.setStatus("establishing encryption…", Theme.textSecondary(), false);
                client.restartSecureChat(peerId);
            }
        });
        menu.add(rekey);

        menu.show(anchor, 0, anchor.getHeight());
    }

    /** Refreshes the chat list, e.g. after history changes outside the open conversation. */
    public void refreshSidebar() {
        sidebar.reload();
    }

    /** Used by {@code Main} to jump straight into a conversation from a command-line argument. */
    public void openConversation(String peerId) {
        sidebar.openConversation(peerId);
    }
}
