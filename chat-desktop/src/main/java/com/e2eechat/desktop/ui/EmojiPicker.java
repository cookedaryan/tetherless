package com.e2eechat.desktop.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Telegram's emoji panel: a category strip above a scrolling grid.
 *
 * <p>The glyphs are literals in a UTF-8 source file, which only works because the build now pins
 * {@code options.encoding = 'UTF-8'}. Without that pin javac reads sources with the platform
 * charset, which is exactly how the old composer's paperclip and send glyphs reached the screen as
 * {@code "??"}. Rendering also needs a colour emoji font, resolved per platform by
 * {@link Theme#EMOJI_FAMILY}.
 */
public class EmojiPicker extends JPopupMenu {

    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("😀", new String[]{ // smileys
                "😀", "😃", "😄", "😁", "😆",
                "😅", "😂", "🤣", "😊", "😇",
                "🙂", "🙃", "😉", "😌", "😍",
                "🥰", "😘", "😗", "😚", "😋",
                "😛", "😜", "🤪", "😝", "🤑",
                "🤗", "🤭", "🤔", "🤐", "🤨",
                "😐", "😑", "😶", "😏", "😒",
                "🙄", "😬", "🤥", "😌", "😔",
                "😪", "🤤", "😴", "😷", "🤒",
                "🤕", "🤢", "🤮", "🤧", "🥵",
                "🥶", "🥴", "😵", "🤯", "🤠",
                "😎", "🤓", "🧐", "😕", "😟",
                "🙁", "😮", "😯", "😲", "😳",
                "🥺", "😦", "😧", "😨", "😰",
                "😥", "😢", "😭", "😱", "😖",
                "😣", "😞", "😓", "😩", "😫",
                "🥱", "😤", "😡", "😠", "🤬",
        });
        CATEGORIES.put("👍", new String[]{ // gestures & people
                "👍", "👎", "👌", "✌️", "🤞",
                "🤟", "🤘", "🤙", "👈", "👉",
                "👆", "👇", "☝️", "✋", "🤚",
                "🖐️", "👏", "🙌", "🤝", "🙏",
                "✍️", "💪", "🦵", "👀", "🧠",
                "👶", "👦", "👧", "👨", "👩",
                "🧑", "👴", "👵", "👮", "👷",
                "💂", "🕵️", "🤵", "👰", "🤰",
        });
        CATEGORIES.put("❤️", new String[]{ // hearts & symbols
                "❤️", "🧡", "💛", "💚", "💙",
                "💜", "🖤", "🤍", "🤎", "💔",
                "❣️", "💕", "💞", "💓", "💗",
                "💖", "💘", "💝", "✨", "⭐", "🌟",
                "💫", "⚡", "🔥", "💥", "🎉",
                "🎊", "🎈", "🎁", "🏆", "🥇",
                "✅", "❌", "❗", "❓", "💯", "🔒", "🔑",
        });
        CATEGORIES.put("🐶", new String[]{ // animals & nature
                "🐶", "🐱", "🐭", "🐹", "🐰",
                "🦊", "🐻", "🐼", "🐨", "🐯",
                "🦁", "🐮", "🐷", "🐸", "🐵",
                "🐔", "🐧", "🐦", "🦆", "🦉",
                "🦇", "🐝", "🐛", "🦋", "🐌",
                "🐞", "🐟", "🐳", "🐋", "🦈",
                "🌳", "🌻", "🌸", "🌹", "🌷",
                "🍁", "🍄", "🌙", "☀️", "☁️",
        });
        CATEGORIES.put("🍕", new String[]{ // food & drink
                "🍕", "🍔", "🍟", "🌭", "🥪",
                "🌮", "🌯", "🥗", "🍜", "🍱",
                "🍣", "🥩", "🍗", "🥚", "🍞",
                "🧀", "🍪", "🎂", "🍰", "🍩",
                "🍫", "🍭", "🍬", "☕", "🍵",
                "🥤", "🍺", "🍷", "🍸", "🥂",
                "🍎", "🍌", "🍇", "🍓", "🍉",
        });
        CATEGORIES.put("⚽", new String[]{ // activity & travel
                "⚽", "🏀", "🏈", "⚾", "🎾", "🏐",
                "🎱", "🏓", "🏸", "🥊", "🎯",
                "🎮", "🎲", "🎸", "🎵", "🎧",
                "🎤", "🎬", "📷", "🚗", "🚕",
                "🚌", "🚲", "✈️", "🚀", "🚢",
                "🏖️", "🏔️", "🗼", "🗽",
        });
    }

    private final Consumer<String> onPick;
    private final JPanel grid = new JPanel();

    public EmojiPicker(Consumer<String> onPick) {
        this.onPick = onPick;
        setBorder(BorderFactory.createLineBorder(Theme.divider()));
        setBackground(Theme.composerBg());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.composerBg());
        root.setPreferredSize(new Dimension(360, 320));

        JPanel tabs = new JPanel();
        tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));
        tabs.setBackground(Theme.composerBg());
        tabs.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.divider()));

        for (Map.Entry<String, String[]> entry : CATEGORIES.entrySet()) {
            JButton tab = new JButton(entry.getKey());
            tab.setFont(Theme.emojiFont(17f));
            tab.setContentAreaFilled(false);
            tab.setBorderPainted(false);
            tab.setFocusPainted(false);
            tab.setCursor(new Cursor(Cursor.HAND_CURSOR));
            tab.setPreferredSize(new Dimension(52, 40));
            tab.addActionListener(e -> showCategory(entry.getValue()));
            tabs.add(tab);
        }

        grid.setLayout(new GridLayout(0, 8, 2, 2));
        grid.setBackground(Theme.composerBg());
        grid.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JScrollPane scroll = new JScrollPane(grid);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(Theme.composerBg());

        root.add(tabs, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        add(root);

        showCategory(CATEGORIES.values().iterator().next());
    }

    private void showCategory(String[] emojis) {
        grid.removeAll();
        for (String emoji : emojis) {
            JButton cell = new JButton(emoji);
            cell.setFont(Theme.emojiFont(21f));
            cell.setContentAreaFilled(false);
            cell.setBorderPainted(false);
            cell.setFocusPainted(false);
            cell.setOpaque(false);
            cell.setCursor(new Cursor(Cursor.HAND_CURSOR));
            cell.setPreferredSize(new Dimension(38, 38));
            cell.setMargin(new java.awt.Insets(0, 0, 0, 0));
            cell.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    cell.setContentAreaFilled(true);
                    cell.setBackground(Theme.inputBg());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    cell.setContentAreaFilled(false);
                }
            });
            // Insert without closing, so several emoji can be picked in one visit — Telegram's
            // behaviour. The popup closes when focus moves away.
            cell.addActionListener(e -> onPick.accept(emoji));
            grid.add(cell);
        }
        grid.revalidate();
        grid.repaint();
    }
}
