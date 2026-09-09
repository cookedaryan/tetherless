package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Searching one conversation, with results you can act on.
 *
 * <p>This was a prompt dialog followed by a second dialog holding the matches as text. You could
 * read them and go nowhere, which is the definition of a dead end.
 */
public class SearchPanel extends JPanel {

    private static final int MAX_HITS = 50;
    private static final int SNIPPET_CHARS = 70;

    private final ChatClient client;
    private final String peerId;
    private final Consumer<ChatMessage> onSelect;
    private final JPanel results = new JPanel();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM");

    public SearchPanel(ChatClient client, String peerId, Consumer<ChatMessage> onSelect) {
        this.client = client;
        this.peerId = peerId;
        this.onSelect = onSelect;

        setOpaque(false);
        setLayout(new BorderLayout());

        final JTextField query = new JTextField();
        query.setFont(Theme.font(Font.PLAIN, 14f));
        query.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                search(query.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                search(query.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                search(query.getText());
            }
        });

        results.setOpaque(false);
        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(results);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        top.add(query, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    private void search(String text) {
        results.removeAll();
        if (text == null || text.trim().isEmpty()) {
            revalidate();
            repaint();
            return;
        }

        List<ChatMessage> hits = client.getMessageRepository()
                .searchMessages(client.getClientId(), text.trim(), MAX_HITS);
        // The repository searches everything this user can see; this panel is about one peer.
        // Same filter the old dialog applied.
        hits.removeIf(m -> !m.getSender().equals(peerId) && !m.getReceiver().equals(peerId));

        if (hits.isEmpty()) {
            JLabel empty = new JLabel("No messages found");
            empty.setFont(Theme.font(Font.PLAIN, 13f));
            Theme.followForeground(empty, Theme::textSecondary);
            empty.setBorder(BorderFactory.createEmptyBorder(16, 12, 0, 12));
            results.add(empty);
        } else {
            for (ChatMessage hit : hits) {
                results.add(resultRow(hit));
            }
        }
        revalidate();
        repaint();
    }

    private JPanel resultRow(final ChatMessage hit) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel who = new JLabel(client.displayNameFor(hit.getSender())
                + "  ·  " + dateFormat.format(new Date(hit.getTimestamp())));
        who.setFont(Theme.font(Font.PLAIN, 11f));
        Theme.followForeground(who, Theme::textSecondary);

        String content = hit.getContent() == null ? "" : hit.getContent();
        JLabel snippet = new JLabel(content.length() > SNIPPET_CHARS
                ? content.substring(0, SNIPPET_CHARS) + "…" : content);
        snippet.setFont(Theme.font(Font.PLAIN, 13f));
        Theme.followForeground(snippet, Theme::textPrimary);

        row.add(who, BorderLayout.NORTH);
        row.add(snippet, BorderLayout.CENTER);
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                onSelect.accept(hit);
            }
        });
        return row;
    }
}
