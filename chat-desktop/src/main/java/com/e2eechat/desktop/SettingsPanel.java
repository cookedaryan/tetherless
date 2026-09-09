package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Motion;
import com.e2eechat.desktop.ui.Sheet;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.ToggleSwitch;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.function.Consumer;

/**
 * The switches: how the app looks, and what it says to the network without being asked.
 *
 * <h2>What is not here any more</h2>
 * Identity, the relay and the version used to share this sheet, because a popup menu with three
 * items had nowhere else to put them. They are their own drawer destinations now - a settings list
 * is for things you change, and none of those three were.
 */
public class SettingsPanel extends JPanel {

    public SettingsPanel() {
        setLayout(new BorderLayout());
        setOpaque(false);

        JPanel body = Sheet.column();

        body.add(Sheet.title("Appearance"));
        body.add(Sheet.row(TgIcons.palette(19), "Night mode",
                "Follows this setting, not the system theme.",
                toggle(Theme.isDark(), Theme::setDark)));
        body.add(Sheet.row(TgIcons.settings(19), "Reduce motion",
                "Turns off the animations throughout the app.",
                toggle(Motion.isReducedMotion(), Motion::setReducedMotion)));

        body.add(Sheet.title("Notifications"));
        body.add(Sheet.row(TgIcons.info(19), "Notify me about new messages",
                "Only when the window is not in front.",
                toggle(DesktopConfig.notificationsPreference(),
                        DesktopConfig::setNotificationsPreference)));
        body.add(Sheet.row(TgIcons.lock(19), "Include the message text",
                "Off by default. A notification is handed to Windows, which shows it on the lock "
                        + "screen and keeps it in the notification centre — so text that was "
                        + "encrypted the whole way here would be copied out of the app in the "
                        + "clear.",
                toggle(DesktopConfig.notificationPreviewPreference(),
                        DesktopConfig::setNotificationPreviewPreference)));

        body.add(Sheet.title("Privacy"));
        body.add(updateChecksRow());

        add(Sheet.scroll(body), BorderLayout.CENTER);
    }

    /**
     * The update-check switch, showing what is actually in force.
     *
     * <p>It used to render the stored preference, which is not the same thing: a client with
     * {@code updates=false} in its {@code config.properties} showed the switch <strong>on</strong>
     * while never checking for anything. Where a rung above the toggle has decided, the switch
     * shows that decision, cannot be flipped, and the row says who decided - a switch that moves
     * and changes nothing is worse than no switch.
     *
     * <p>Nothing here writes {@code tetherless.updates}. It bought nothing, because
     * {@code UpdateChecker} reads that property once at startup, and it overwrote the
     * {@code false} that {@code DesktopConfig.applyTlsProperties} publishes for a
     * deployment-mandated off - the one override the precedence chain exists to forbid.
     */
    private static JComponent updateChecksRow() {
        Boolean pinned = DesktopConfig.updateChecksOverride();
        String subtitle = "Asks GitHub whether a newer version exists. GitHub, and anyone watching "
                + "the network, learns this address runs Tetherless and roughly when it started.";
        if (pinned != null) {
            subtitle += " Set by this installation's configuration, so it cannot be changed here.";
        }

        ToggleSwitch control = new ToggleSwitch(DesktopConfig.effectiveUpdateChecks());
        if (pinned == null) {
            control.onChange(DesktopConfig::setUpdateChecksPreference);
        } else {
            control.setEnabled(false);
        }
        return Sheet.row(TgIcons.info(19), "Check for updates on startup", subtitle, control);
    }

    private static ToggleSwitch toggle(boolean initial, Consumer<Boolean> onChange) {
        ToggleSwitch control = new ToggleSwitch(initial);
        control.onChange(onChange);
        return control;
    }
}
