package com.e2eechat.desktop.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

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
        for (Listener l : new ArrayList<>(LISTENERS)) {
            l.onThemeChanged();
        }
    }

    public static void toggle() {
        setDark(!dark);
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

    /** Sidebar / chat-list background. */
    public static Color sidebarBg() {
        return pick(0xFFFFFF, 0x17212B);
    }

    /** Chat-list row under the pointer. */
    public static Color sidebarHover() {
        return pick(0xF4F4F5, 0x202B36);
    }

    /** Chat-list row for the open conversation. */
    public static Color sidebarSelected() {
        return pick(0x419FD9, 0x2B5278);
    }

    /** Top bar above the transcript and above the chat list. */
    public static Color headerBg() {
        return pick(0xFFFFFF, 0x17212B);
    }

    /** Hairline rules between panels. */
    public static Color divider() {
        return pick(0xE4E8EB, 0x101921);
    }

    /** Base colour behind the wallpaper pattern. */
    public static Color chatBg() {
        return pick(0xD5DBE3, 0x0E1621);
    }

    /** Wallpaper pattern ink, drawn at low alpha over {@link #chatBg()}. */
    public static Color chatPattern() {
        return pick(0xC2CBD6, 0x131E29);
    }

    public static Color bubbleIn() {
        return pick(0xFFFFFF, 0x182533);
    }

    public static Color bubbleOut() {
        return pick(0xEFFDDE, 0x2B5278);
    }

    /** Bubble background for a message that failed authentication or decryption. */
    public static Color bubbleError() {
        return pick(0xFBE3E3, 0x4A2226);
    }

    public static Color textPrimary() {
        return pick(0x000000, 0xFFFFFF);
    }

    public static Color textSecondary() {
        return pick(0x707579, 0x7D8B99);
    }

    /** Timestamp inside an incoming bubble. */
    public static Color timeIn() {
        return pick(0xA1AAB3, 0x6D7F8F);
    }

    /** Timestamp inside an outgoing bubble. */
    public static Color timeOut() {
        return pick(0x62B25A, 0x8DA5BF);
    }

    /** Delivery ticks inside an outgoing bubble. */
    public static Color tick() {
        return pick(0x5DC452, 0x72A6D8);
    }

    /** Accent used for buttons, links, badges and the secure-session indicator. */
    public static Color accent() {
        return pick(0x3390EC, 0x64B5EF);
    }

    public static Color accentHover() {
        return pick(0x2B82D9, 0x529BDB);
    }

    /** Unread-count pill on an unmuted chat. */
    public static Color badge() {
        return pick(0x3390EC, 0x64B5EF);
    }

    public static Color badgeText() {
        return pick(0xFFFFFF, 0x17212B);
    }

    /** Fill behind the search box and other inset controls. */
    public static Color inputBg() {
        return pick(0xF1F1F1, 0x242F3D);
    }

    /** Fill behind the message composer. */
    public static Color composerBg() {
        return pick(0xFFFFFF, 0x17212B);
    }

    /** Icon glyphs in their resting state. */
    public static Color icon() {
        return pick(0x707579, 0x7D8B99);
    }

    public static Color iconHover() {
        return pick(0x3E4144, 0xB6C2CE);
    }

    /** Floating pill used for date separators and the "unread messages" rule. */
    public static Color floatingPill() {
        return dark ? new Color(0x18, 0x25, 0x33, 0xCC) : new Color(0x00, 0x00, 0x00, 0x40);
    }

    public static Color floatingPillText() {
        return Color.WHITE;
    }

    /** Warning red for key-change and decryption-failure notices. */
    public static Color danger() {
        return pick(0xE53935, 0xEF5350);
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
