package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.UpdateBanner;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The notice strip itself.
 *
 * <p>Only the two things about it that can silently go wrong: that it takes up no room until there
 * is something to say, and that when there is, the version and the link both reach the user.
 * Skipped where there is no display, which is how CI runs.
 */
public class UpdateBannerTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    @Test
    public void itIsInvisibleUntilThereIsSomethingToSay() {
        assertFalse(new UpdateBanner().isVisible());
    }

    @Test
    public void itShowsTheVersionAndCarriesTheLink() {
        UpdateBanner banner = new UpdateBanner();

        banner.show("1.2.0", "https://github.com/cookedaryan/tetherless/releases/tag/v1.2.0");

        assertTrue(banner.isVisible());
        JLabel message = firstLabel(banner);
        assertNotNull("the banner has no label", message);
        assertTrue("the version is not shown: " + message.getText(),
                message.getText().contains("1.2.0"));
        assertTrue("the link is not reachable from the banner",
                message.getToolTipText().endsWith("/releases/tag/v1.2.0"));
    }

    private static JLabel firstLabel(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel) {
                return (JLabel) child;
            }
        }
        return null;
    }
}
