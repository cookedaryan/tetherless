package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.desktop.ui.Avatars;
import com.e2eechat.desktop.ui.Sheet;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.ToggleSwitch;

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
 * The drawer behind the hamburger: who you are at the top, where you can go underneath.
 *
 * <h2>Why not a popup menu</h2>
 * This was a {@code JPopupMenu} of three {@code JMenuItem}s. A platform popup cannot show an
 * avatar, cannot give a destination a subtitle, and looks like a right-click menu because that is
 * what it is - no amount of look-and-feel theming makes it read as part of the application. The
 * app already slides sheets in from the edges for Settings and Chat info; the drawer is the same
 * mechanism, so the menu now matches everything else rather than being the one dated surface.
 *
 * <p>The header is the profile: the avatar and name identify which of several profiles this window
 * is, which matters when two are open side by side.
 *
 * <p>There is no "New chat" row. Pasting a peer id into the search box starts the conversation, so
 * a menu entry for it would be a second route to something the box above the list already does.
 */
public class NavigationDrawer extends JPanel {

    /** Where a drawer row can send the user. The window owns the panels; this only names them. */
    public interface Destinations {
        void profile();

        void settings();

        void relay();

        void about();
    }

    public NavigationDrawer(ChatClient client, Destinations destinations) {
        setLayout(new BorderLayout());
        setOpaque(false);

        JPanel body = Sheet.column();
        body.add(buildHeader(client, destinations));
        body.add(Sheet.spacer(6));

        body.add(Sheet.navRow(TgIcons.person(19), "Profile",
                "Your name and the id people add you by", destinations::profile));
        body.add(Sheet.navRow(TgIcons.settings(19), "Settings",
                "Appearance and privacy", destinations::settings));
        body.add(Sheet.navRow(TgIcons.plug(19), "Relay",
                "Where this client connects, and what it trusts", destinations::relay));
        body.add(Sheet.navRow(TgIcons.info(19), "About",
                "Version and build", destinations::about));

        // Night mode stays on the drawer itself rather than behind Settings: it is the one option
        // people flip often enough to want without opening anything.
        ToggleSwitch night = new ToggleSwitch(Theme.isDark());
        night.onChange(Theme::setDark);
        body.add(Sheet.spacer(6));
        body.add(Sheet.row(TgIcons.palette(19), "Night mode", null, night));

        add(Sheet.scroll(body), BorderLayout.CENTER);
    }

    /** Avatar, display name and peer id - the drawer's identity block, clickable into Profile. */
    private JComponent buildHeader(final ChatClient client, final Destinations destinations) {
        final String name = client.getLocalDisplayName();

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(24, 20, 16, 20));

        JComponent avatar = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(60, 60);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 60);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Avatars.paint(g2, client.getClientId(), name, 0, 0, 60);
                g2.dispose();
            }
        };
        header.add(leftAligned(avatar));
        header.add(Sheet.spacer(12));

        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(Theme.font(Font.BOLD, 16.5f));
        Theme.followForeground(nameLabel, Theme::textPrimary);
        header.add(leftAligned(nameLabel));
        header.add(Sheet.spacer(3));

        JLabel idLabel = new JLabel(PeerId.forDisplay(client.getClientId()));
        idLabel.setFont(Theme.font(Font.PLAIN, 12f));
        Theme.followForeground(idLabel, Theme::textSecondary);
        header.add(leftAligned(idLabel));

        return header;
    }

    private static JComponent leftAligned(JComponent child) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                child.getPreferredSize().height));
        holder.add(child, BorderLayout.WEST);
        return holder;
    }
}
