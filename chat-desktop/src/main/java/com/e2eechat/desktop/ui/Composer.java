package com.e2eechat.desktop.ui;

import com.e2eechat.desktop.ChatMessage;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * Telegram's message composer: a rounded card holding an emoji button, a text area that grows with
 * its content, an attachment button, and a send button.
 *
 * <p>Behaviours copied from Telegram Desktop:
 * <ul>
 *   <li><kbd>Enter</kbd> sends, <kbd>Shift</kbd>+<kbd>Enter</kbd> inserts a newline — the old
 *       single-line {@code JTextField} could not express a multi-line message at all;</li>
 *   <li>the field grows from one line to at most six, then scrolls;</li>
 *   <li>a reply banner sits above the field, with a cancel button;</li>
 *   <li>typing fires a throttled notification so the peer sees a typing indicator, and a
 *       quiet period cancels it.</li>
 * </ul>
 */
public class Composer extends JPanel {

    private static final int MAX_VISIBLE_LINES = 6;
    /** Repeat interval for typing pings while the user keeps typing. */
    private static final int TYPING_PING_MS = 3000;
    /** Silence after which the peer is told typing stopped. */
    private static final int TYPING_IDLE_MS = 4000;

    private final JTextArea input = new JTextArea(1, 20);
    private final ReplyBanner replyBanner = new ReplyBanner();
    private final IconButton emojiButton;
    private final IconButton sendButton;

    private final Timer typingIdleTimer;
    private long lastTypingPing;

    private Consumer<String> onSend = t -> { };
    private Consumer<Boolean> onTypingChanged = t -> { };

    private ChatMessage replyTarget;

    public Composer() {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(6, 14, 12, 14));

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(Theme.bubbleText());
        input.setBorder(BorderFactory.createEmptyBorder(9, 4, 9, 4));
        input.setOpaque(false);
        input.setEnabled(false);

        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setBorder(null);
        inputScroll.setOpaque(false);
        inputScroll.getViewport().setOpaque(false);
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        emojiButton = new IconButton(() -> TgIcons.emoji(22), "Emoji");
        sendButton = new IconButton(() -> TgIcons.send(21), "Send",
                Theme::accent, Theme::accentHover);

        emojiButton.addActionListener(e -> openEmojiPicker());
        sendButton.addActionListener(e -> send());

        Card card = new Card();
        card.setLayout(new BorderLayout(4, 0));
        card.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        card.add(emojiButton, BorderLayout.WEST);
        card.add(inputScroll, BorderLayout.CENTER);

        JPanel trailing = new JPanel();
        trailing.setOpaque(false);
        trailing.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        trailing.add(sendButton);
        card.add(trailing, BorderLayout.EAST);

        JPanel stack = new JPanel(new BorderLayout());
        stack.setOpaque(false);
        stack.add(replyBanner, BorderLayout.NORTH);
        stack.add(card, BorderLayout.CENTER);
        add(stack, BorderLayout.CENTER);

        wireKeys();
        wireTyping();

        typingIdleTimer = new Timer(TYPING_IDLE_MS, e -> {
            onTypingChanged.accept(false);
            lastTypingPing = 0;
        });
        typingIdleTimer.setRepeats(false);

        Theme.addListener(() -> {
            input.setForeground(Theme.textPrimary());
            input.setCaretColor(Theme.accent());
            repaint();
        });
        input.setForeground(Theme.textPrimary());
        input.setCaretColor(Theme.accent());
    }

    // ------------------------------------------------------------------ wiring

    private void wireKeys() {
        // Enter sends; Shift+Enter falls through to the text area's own newline insertion.
        input.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "tg-send");
        input.getActionMap().put("tg-send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });
        input.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                        "insert-break");
        input.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "tg-cancel-reply");
        input.getActionMap().put("tg-cancel-reply", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearReply();
            }
        });
    }

    private void wireTyping() {
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                resizeToContent();
                pingTyping();
            }
        });
    }

    /**
     * Notifies the peer at most once every {@link #TYPING_PING_MS}, and schedules a "stopped"
     * notice. Sending one frame per keystroke would flood the relay for no benefit.
     */
    private void pingTyping() {
        if (!input.isEnabled() || input.getText().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTypingPing > TYPING_PING_MS) {
            lastTypingPing = now;
            onTypingChanged.accept(true);
        }
        typingIdleTimer.restart();
    }

    /** Grows the field with its content up to {@link #MAX_VISIBLE_LINES}, then lets it scroll. */
    private void resizeToContent() {
        int lineHeight = input.getFontMetrics(input.getFont()).getHeight();
        int lines = Math.max(1, Math.min(MAX_VISIBLE_LINES, countDisplayLines()));
        int target = lines * lineHeight + 18;
        Dimension current = input.getPreferredSize();
        if (current.height != target) {
            input.setPreferredSize(new Dimension(current.width, target));
            revalidate();
        }
    }

    private int countDisplayLines() {
        try {
            int total = 0;
            int offset = 0;
            int length = input.getDocument().getLength();
            while (offset <= length) {
                int end = javax.swing.text.Utilities.getRowEnd(input, offset);
                if (end < 0) {
                    break;
                }
                total++;
                offset = end + 1;
            }
            return Math.max(total, 1);
        } catch (Exception e) {
            // Utilities throws while the view is still being laid out; one line is a safe guess.
            return 1;
        }
    }

    private void openEmojiPicker() {
        EmojiPicker picker = new EmojiPicker(emoji -> {
            input.insert(emoji, input.getCaretPosition());
            input.requestFocusInWindow();
        });
        picker.show(emojiButton, 0, -330);
    }

    private void send() {
        String text = input.getText().trim();
        if (text.isEmpty() || !input.isEnabled()) {
            return;
        }
        onSend.accept(text);
        input.setText("");
        clearReply();
        typingIdleTimer.stop();
        onTypingChanged.accept(false);
        lastTypingPing = 0;
        resizeToContent();
    }

    // ------------------------------------------------------------------- API

    public void setOnSend(Consumer<String> onSend) {
        this.onSend = onSend == null ? t -> { } : onSend;
    }

    public void setOnTypingChanged(Consumer<Boolean> onTypingChanged) {
        this.onTypingChanged = onTypingChanged == null ? t -> { } : onTypingChanged;
    }

    /**
     * Enables or disables composing. Disabled whenever the session is not established — the client
     * must never offer a plaintext fallback.
     */
    public void setComposingEnabled(boolean enabled) {
        input.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        emojiButton.setEnabled(enabled);
        repaint();
    }

    public void setReplyTarget(ChatMessage message, String senderLabel) {
        this.replyTarget = message;
        replyBanner.showQuote(senderLabel, message.getContent());
        revalidate();
        repaint();
        input.requestFocusInWindow();
    }

    public ChatMessage getReplyTarget() {
        return replyTarget;
    }

    public void clearReply() {
        this.replyTarget = null;
        replyBanner.dismiss();
        revalidate();
        repaint();
    }

    /**
     * Puts a refused message back in the box.
     *
     * <p>The composer clears itself as soon as it hands text over, which is right when the message
     * goes and wrong when it does not: a send that is refused - no session yet, or a key that has
     * reached its send budget - otherwise takes the words off the screen with nothing to show for
     * them. Anything the user has typed since wins, because their newer text is the one they are
     * looking at.
     */
    public void restoreDraft(String text) {
        if (text == null || text.isEmpty() || !input.getText().isEmpty()) {
            return;
        }
        input.setText(text);
        resizeToContent();
        focusInput();
    }

    public void focusInput() {
        SwingUtilities.invokeLater(input::requestFocusInWindow);
    }

    // -------------------------------------------------------------- sub-views

    /** The rounded white card the composer controls sit on. */
    private static class Card extends JPanel {
        Card() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, Theme.isDark() ? 50 : 20));
            g2.fill(new RoundRectangle2D.Double(0, 2, getWidth(), getHeight() - 2, 20, 20));
            g2.setColor(Theme.composerBg());
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight() - 2, 20, 20));
            g2.dispose();
        }
    }

    /** "Reply to <name>" strip above the input, with the quoted text and a cancel button. */
    private class ReplyBanner extends JPanel {
        private final JLabel nameLabel = new JLabel();
        private final JLabel previewLabel = new JLabel();

        ReplyBanner() {
            setOpaque(false);
            setLayout(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 6));
            setVisible(false);

            nameLabel.setFont(Theme.font(Font.BOLD, 12.5f));
            previewLabel.setFont(Theme.font(Font.PLAIN, 12.5f));

            JPanel text = new JPanel(new java.awt.GridLayout(2, 1));
            text.setOpaque(false);
            text.add(nameLabel);
            text.add(previewLabel);

            IconButton cancel = new IconButton(() -> TgIcons.close(16), "Cancel reply");
            cancel.addActionListener(e -> clearReply());

            JLabel icon = new JLabel(TgIcons.reply(18)) {
                @Override
                public Color getForeground() {
                    return Theme.accent();
                }
            };
            icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(cancel, BorderLayout.EAST);
        }

        void showQuote(String sender, String preview) {
            nameLabel.setText(sender);
            String flat = preview.replace('\n', ' ');
            previewLabel.setText(flat.length() > 60 ? flat.substring(0, 59) + "…" : flat);
            setVisible(true);
        }

        void dismiss() {
            setVisible(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.composerBg());
            g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight() + 20, 20, 20));
            g2.setColor(Theme.accent());
            g2.fillRect(30, 6, 2, getHeight() - 12);
            g2.dispose();
            nameLabel.setForeground(Theme.accent());
            previewLabel.setForeground(Theme.textSecondary());
        }
    }
}
