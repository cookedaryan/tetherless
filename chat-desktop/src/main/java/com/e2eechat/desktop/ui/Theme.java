package com.e2eechat.desktop.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.UIManager;

/**
 * Central palette and typography for the Telegram Desktop look.
 *
 * <p>Colour values are taken from Telegram Desktop's bundled "Day" and "Night" themes so the
 * client reads as the real thing rather than an approximation. Every colour used anywhere in the
 * UI must come from here — no literal {@code new Color(...)} in components — otherwise the dark
 * mode toggle leaves stragglers behind.
 *
 * <p>Not thread-safe: all access must happen on the Swing event dispatch thread.
 */
public final class Theme {

    /** Notified after {@link #setDark(boolean)} changes the palette. */
    public interface Listener {
        void onThemeChanged();
    }

    private static final Preferences PREFS = Preferences.userRoot().node("com/e2eechat/desktop");
    private static final String PREF_DARK = "darkMode";

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static boolean dark = PREFS.getBoolean(PREF_DARK, false);

    private Theme() {
    }

    public static boolean isDark() {
        return dark;
    }

    public static void setDark(boolean value) {
        if (dark == value) {
            return;
        }
        dark = value;
        PREFS.putBoolean(PREF_DARK, value);

        // Order matters. The look and feel and its palette go first, then every open window is
        // restyled, and only then do the custom-painted components repaint. Firing the listeners
        // first leaves a frame half in one theme and half in the other.
        installLookAndFeel();
        if (UIManager.getLookAndFeel() instanceof FlatLaf) {
            FlatLaf.updateUI();
        }
        for (Listener l : new ArrayList<>(LISTENERS)) {
            l.onThemeChanged();
        }
    }

    public static void toggle() {
        setDark(!dark);
    }

    /**
     * Installs the look and feel matching the current palette, and feeds it that palette.
     *
     * <p>FlatLaf has been a declared dependency that nothing installed, so every stock component -
     * popup menus, scrollbars, tooltips, carets, dialogs - rendered in default Metal regardless of
     * the toggle. Pushing the palette in as well means a popup opened next to a bubble is the same
     * dark rather than a neighbouring one.
     *
     * <p>Never throws. A look and feel that will not load is not a reason to fail startup: the
     * custom-painted components still follow the palette and the application stays usable.
     */
    public static void installLookAndFeel() {
        // Let FlatLaf draw the title bar instead of the platform. Without this the window keeps a
        // light Windows caption above a dark application - the one strip of the window the theme
        // could not reach. The flag is read when a frame is created, so it has to be set before
        // the first one exists; calling it again on a theme toggle is harmless.
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);

        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (Exception e) {
            return;
        }
        applyPaletteToUiManager();
    }

    private static void applyPaletteToUiManager() {
        UIManager.put("PopupMenu.background", sidebarBg());
        UIManager.put("MenuItem.background", sidebarBg());
        UIManager.put("MenuItem.foreground", textPrimary());
        UIManager.put("MenuItem.selectionBackground", sidebarSelected());
        UIManager.put("MenuItem.selectionForeground", textPrimary());
        UIManager.put("CheckBoxMenuItem.background", sidebarBg());
        UIManager.put("CheckBoxMenuItem.foreground", textPrimary());
        UIManager.put("Separator.foreground", divider());
        UIManager.put("ScrollBar.thumb", divider());
        UIManager.put("ScrollBar.track", sidebarBg());
        UIManager.put("ToolTip.background", headerBg());
        UIManager.put("ToolTip.foreground", textPrimary());
        UIManager.put("TextField.background", inputBg());
        UIManager.put("TextField.foreground", textPrimary());
        UIManager.put("TextField.caretForeground", textPrimary());
        UIManager.put("TextField.selectionBackground", accent());
        UIManager.put("TextArea.background", inputBg());
        UIManager.put("TextArea.foreground", textPrimary());
        UIManager.put("TextArea.caretForeground", textPrimary());
        UIManager.put("TextArea.selectionBackground", accent());
        UIManager.put("PasswordField.background", inputBg());
        UIManager.put("PasswordField.foreground", textPrimary());
        UIManager.put("PasswordField.caretForeground", textPrimary());
        UIManager.put("Panel.background", sidebarBg());
        UIManager.put("OptionPane.background", sidebarBg());
        UIManager.put("OptionPane.messageForeground", textPrimary());
        UIManager.put("Component.focusColor", accent());
        UIManager.put("Component.borderColor", divider());

        // The title bar FlatLaf now draws for us, painted to match the header beneath it so the
        // top of the window reads as one surface rather than a strip bolted above the app.
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("TitlePane.background", headerBg());
        UIManager.put("TitlePane.foreground", textPrimary());
        UIManager.put("TitlePane.inactiveBackground", headerBg());
        UIManager.put("TitlePane.inactiveForeground", textSecondary());
        UIManager.put("TitlePane.buttonHoverBackground", sidebarHover());
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /**
     * Registers {@code onChange} for as long as {@code component} is on screen.
     *
     * <p>The listener list is static and lives for the life of the process, so a component that
     * registers in its constructor and never deregisters keeps itself - and everything it
     * references - alive forever. That is harmless for the main window, which is a singleton, and a
     * genuine leak for anything transient: the sign-in dialog is constructed afresh on every failed
     * attempt.
     */
    public static void follow(javax.swing.JComponent component, Runnable onChange) {
        Listener listener = onChange::run;
        component.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) == 0) {
                return;
            }
            if (component.isDisplayable()) {
                if (!LISTENERS.contains(listener)) {
                    LISTENERS.add(listener);
                }
            } else {
                LISTENERS.remove(listener);
            }
        });
        if (component.isDisplayable()) {
            LISTENERS.add(listener);
        }
    }

    // ---------------------------------------------------------------- colours

    private static Color pick(int lightRgb, int darkRgb) {
        return new Color(dark ? darkRgb : lightRgb);
    }

    /**
     * The shell behind the panels. The window is a set of cards floating on this, rather than
     * panels butted against each other, which is what gives the layout its air.
     */
    public static Color pageBg() {
        return pick(0xF4F6FA, 0x0B111C);
    }

    /** Sidebar / chat-list background. */
    public static Color sidebarBg() {
        return pick(0xF7F9FC, 0x121A27);
    }

    /** Chat-list row under the pointer. */
    public static Color sidebarHover() {
        return pick(0xEFF2F8, 0x1A2433);
    }

    /**
     * Chat-list row for the open conversation - a lifted card, not a block of accent colour, so
     * the selected row reads as raised off the list rather than painted over.
     */
    public static Color sidebarSelected() {
        return pick(0xFFFFFF, 0x223047);
    }

    /** Text on the selected row. It is no longer safe to assume white: in light mode the row is. */
    public static Color sidebarSelectedText() {
        return pick(0x111827, 0xFFFFFF);
    }

    /** Soft drop shadow under a raised card. */
    public static Color shadow() {
        return dark ? new Color(0, 0, 0, 90) : new Color(0x8A, 0x99, 0xB5, 46);
    }

    /** Top bar above the transcript and above the chat list. */
    public static Color headerBg() {
        return pick(0xFFFFFF, 0x121A27);
    }

    /** Hairline rules between panels. */
    public static Color divider() {
        return pick(0xE9EDF3, 0x1F2937);
    }

    /** The transcript surface. Flat: the reference design has no wallpaper behind the bubbles. */
    public static Color chatBg() {
        return pick(0xFFFFFF, 0x0E1622);
    }

    /** Kept equal to {@link #chatBg()} so the old pattern renders as nothing. */
    public static Color chatPattern() {
        return chatBg();
    }

    public static Color bubbleIn() {
        return pick(0xF1F4F9, 0x1C2634);
    }

    public static Color bubbleOut() {
        return pick(0xD9E6FE, 0x2B4B7D);
    }

    /** Bubble background for a message that failed authentication or decryption. */
    public static Color bubbleError() {
        return pick(0xFDE8E8, 0x4A2226);
    }

    public static Color textPrimary() {
        return pick(0x111827, 0xE9EEF6);
    }

    public static Color textSecondary() {
        return pick(0x8B95A7, 0x8B95A7);
    }

    /** Timestamp inside an incoming bubble. */
    public static Color timeIn() {
        return pick(0x9AA4B5, 0x71809A);
    }

    /** Timestamp inside an outgoing bubble. */
    public static Color timeOut() {
        return pick(0x6B8FCB, 0x9DB8E0);
    }

    /** Delivery ticks inside an outgoing bubble. */
    public static Color tick() {
        return pick(0x2F6FED, 0x63A0F5);
    }

    /** Accent used for buttons, links, badges and the secure-session indicator. */
    public static Color accent() {
        return pick(0x1668FF, 0x4D8DFF);
    }

    public static Color accentHover() {
        return pick(0x0D57DB, 0x3D7AE8);
    }

    /** Unread-count pill. Coral rather than accent, so an unread count is not another blue. */
    public static Color badge() {
        return pick(0xF4756B, 0xF4756B);
    }

    public static Color badgeText() {
        return pick(0xFFFFFF, 0xFFFFFF);
    }

    /** Fill behind the search box and other inset controls. */
    public static Color inputBg() {
        return pick(0xF1F4F9, 0x1A2433);
    }

    /** Fill behind the message composer. */
    public static Color composerBg() {
        return pick(0xFFFFFF, 0x121A27);
    }

    /** Icon glyphs in their resting state. */
    public static Color icon() {
        return pick(0x8B95A7, 0x8B95A7);
    }

    public static Color iconHover() {
        return pick(0x4B5568, 0xC3CCDA);
    }

    /** Date separators. Quiet text on the transcript, not a pill floating over a wallpaper. */
    public static Color floatingPill() {
        return dark ? new Color(0x1C, 0x26, 0x34, 0xFF) : new Color(0xF1, 0xF4, 0xF9, 0xFF);
    }

    public static Color floatingPillText() {
        return textSecondary();
    }

    /** Warning red for key-change and decryption-failure notices. */
    public static Color danger() {
        return pick(0xE5484D, 0xEF5350);
    }

    // ------------------------------------------------------------- typography

    private static final String UI_FAMILY = resolveFamily(
            "Segoe UI", "Helvetica Neue", "Roboto", "SF Pro Text", Font.SANS_SERIF);

    /**
     * Family that can actually render colour emoji. Falling back to the UI family would render
     * every picker entry as a tofu box, which is why this is resolved separately.
     */
    public static final String EMOJI_FAMILY = resolveFamily(
            "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", "Segoe UI Symbol", UI_FAMILY);

    private static String resolveFamily(String... candidates) {
        List<String> available = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String candidate : candidates) {
            if (available.contains(candidate)) {
                return candidate;
            }
        }
        return Font.SANS_SERIF;
    }

    public static Font font(int style, float size) {
        return new Font(UI_FAMILY, style, 12).deriveFont(style, size);
    }

    public static Font emojiFont(float size) {
        return new Font(EMOJI_FAMILY, Font.PLAIN, 12).deriveFont(size);
    }

    public static Font bubbleText() {
        return font(Font.PLAIN, 14.5f);
    }

    public static Font bubbleMeta() {
        return font(Font.PLAIN, 11f);
    }

    public static Font chatName() {
        return font(Font.BOLD, 14f);
    }

    public static Font chatPreview() {
        return font(Font.PLAIN, 13f);
    }

    public static Font headerTitle() {
        return font(Font.BOLD, 15f);
    }

    public static Font headerSubtitle() {
        return font(Font.PLAIN, 13f);
    }
}
