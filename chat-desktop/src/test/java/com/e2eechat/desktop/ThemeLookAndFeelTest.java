package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Theme;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.UIManager;

import static org.junit.Assert.assertEquals;

/**
 * The bridge between the palette and the stock Swing components.
 *
 * <p>FlatLaf was a declared dependency that nothing installed, so popup menus, scrollbars and
 * dialogs rendered in default Metal whatever the toggle said.
 */
public class ThemeLookAndFeelTest {

    private boolean originalDark;
    private String originalLookAndFeel;

    @Before
    public void remember() {
        originalDark = Theme.isDark();
        originalLookAndFeel = UIManager.getLookAndFeel().getClass().getName();
    }

    @After
    public void restore() throws Exception {
        Theme.setDark(originalDark);
        UIManager.setLookAndFeel(originalLookAndFeel);
    }

    @Test
    public void theDarkPaletteInstallsTheDarkLookAndFeel() {
        Theme.setDark(false);
        Theme.setDark(true);

        assertEquals("com.formdev.flatlaf.FlatDarkLaf",
                UIManager.getLookAndFeel().getClass().getName());
    }

    @Test
    public void theLightPaletteInstallsTheLightLookAndFeel() {
        Theme.setDark(true);
        Theme.setDark(false);

        assertEquals("com.formdev.flatlaf.FlatLightLaf",
                UIManager.getLookAndFeel().getClass().getName());
    }

    /** A popup opened next to a bubble has to be the same dark, not a neighbouring one. */
    @Test
    public void stockComponentsTakeTheirColoursFromThePalette() {
        Theme.setDark(true);
        Theme.installLookAndFeel();

        assertEquals(Theme.sidebarBg(), UIManager.getColor("PopupMenu.background"));
        assertEquals(Theme.textPrimary(), UIManager.getColor("MenuItem.foreground"));
        assertEquals(Theme.inputBg(), UIManager.getColor("TextField.background"));
    }

    @Test
    public void installingTwiceIsSafe() {
        Theme.installLookAndFeel();
        Theme.installLookAndFeel();

        assertEquals(Theme.sidebarBg(), UIManager.getColor("PopupMenu.background"));
    }
}
