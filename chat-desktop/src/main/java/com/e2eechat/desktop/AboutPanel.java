package com.e2eechat.desktop;

import com.e2eechat.core.build.BuildInfo;
import com.e2eechat.desktop.ui.Brandmark;
import com.e2eechat.desktop.ui.Sheet;
import com.e2eechat.desktop.ui.TgIcons;
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
 * Version, commit and channel - the three things a bug report is useless without.
 *
 * <p>The stamp is generated from the commit rather than the wall clock, so a given commit always
 * produces the same values and two people reporting "1.0.0" are talking about the same build.
 */
public class AboutPanel extends JPanel {

    public AboutPanel() {
        setLayout(new BorderLayout());
        setOpaque(false);

        JPanel body = Sheet.column();
        body.add(buildMasthead());

        body.add(Sheet.title("Build"));
        body.add(Sheet.row(TgIcons.info(19), "Version", BuildInfo.version(), null));
        body.add(Sheet.row(TgIcons.check(19), "Commit", BuildInfo.commit(), null));
        body.add(Sheet.row(TgIcons.settings(19), "Channel", BuildInfo.channel(), null));

        add(Sheet.scroll(body), BorderLayout.CENTER);
    }

    private JComponent buildMasthead() {
        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(26, 20, 6, 20));

        JComponent mark = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(64, 64);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 64);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Brandmark.paint(g2, (getWidth() - 64) / 2, 0, 64, 1f);
                g2.dispose();
            }
        };
        card.add(mark);
        card.add(Sheet.spacer(12));

        JLabel name = new JLabel("Tetherless", JLabel.CENTER);
        name.setFont(Theme.font(Font.BOLD, 18f));
        Theme.followForeground(name, Theme::textPrimary);
        card.add(Sheet.fullWidth(name));
        card.add(Sheet.spacer(6));

        card.add(new WrappedLabel(
                "End-to-end encrypted chat. Messages are encrypted on your device and decrypted "
                        + "on your contact's; the relay in the middle only routes ciphertext.",
                11.5f, true));

        return card;
    }
}
