package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.desktop.ui.Avatars;
import com.e2eechat.desktop.ui.CopyButton;
import com.e2eechat.desktop.ui.Sheet;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.WrappedLabel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Who you are, as the other side sees you: avatar, display name, and the peer id to hand out.
 *
 * <p>The id is the only thing another person needs to start a conversation, so it is the single
 * most-copied string in the application and gets a button rather than a menu item.
 */
public class ProfilePanel extends JPanel {

    private final ChatClient client;

    public ProfilePanel(ChatClient client) {
        this.client = client;
        setLayout(new BorderLayout());
        setOpaque(false);

        JPanel body = Sheet.column();
        body.add(buildIdentityCard());
        add(Sheet.scroll(body), BorderLayout.CENTER);
    }

    private JComponent buildIdentityCard() {
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(22, 20, 18, 20));

        final String name = client.getLocalDisplayName();
        JComponent avatar = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(76, 76);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 76);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Avatars.paint(g2, client.getClientId(), name, (getWidth() - 76) / 2, 0, 76);
                g2.dispose();
            }
        };
        card.add(avatar);
        card.add(Sheet.spacer(14));

        JLabel nameLabel = new JLabel(name, JLabel.CENTER);
        nameLabel.setFont(Theme.font(Font.BOLD, 18f));
        nameLabel.setForeground(Theme.textPrimary());
        card.add(Sheet.fullWidth(nameLabel));
        card.add(Sheet.spacer(6));

        JLabel idLabel = new JLabel(PeerId.forDisplay(client.getClientId()), JLabel.CENTER);
        idLabel.setFont(Theme.font(Font.PLAIN, 12.5f));
        idLabel.setForeground(Theme.textSecondary());
        card.add(Sheet.fullWidth(idLabel));
        card.add(Sheet.spacer(14));

        card.add(Sheet.fullWidth(new CopyButton(client.getClientId(), "Copy my id")));
        card.add(Sheet.spacer(10));

        card.add(new WrappedLabel(
                "Your id comes from your identity key, so renaming yourself does not change it.",
                11.5f, true));

        return card;
    }
}
