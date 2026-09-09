package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Motion;
import com.e2eechat.desktop.ui.Sheet;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.ToggleSwitch;

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

        body.add(Sheet.title("Privacy"));
        body.add(Sheet.row(TgIcons.info(19), "Check for updates on startup",
                "Asks GitHub whether a newer version exists. GitHub, and anyone watching the "
                        + "network, learns this address runs Tetherless and roughly when it "
                        + "started.",
                toggle(DesktopConfig.updateChecksPreference(), new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean value) {
                        DesktopConfig.setUpdateChecksPreference(value);
                        System.setProperty(UpdateChecker.ENABLED_PROPERTY, String.valueOf(value));
                    }
                })));

        add(Sheet.scroll(body), BorderLayout.CENTER);
    }

    private static ToggleSwitch toggle(boolean initial, Consumer<Boolean> onChange) {
        ToggleSwitch control = new ToggleSwitch(initial);
        control.onChange(onChange);
        return control;
    }
}
