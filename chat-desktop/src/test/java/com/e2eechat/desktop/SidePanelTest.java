package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Motion;
import com.e2eechat.desktop.ui.SidePanel;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JLabel;
import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel host, on its own.
 *
 * <p>Reduced motion is switched on throughout: {@code Motion.animate} then lands on the end state
 * and runs its completion handler immediately, so opening and dismissing are synchronous and a
 * test does not have to wait on an animation.
 */
public class SidePanelTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    private JFrame frame;
    private boolean originalReducedMotion;

    @Before
    public void setUp() {
        originalReducedMotion = Motion.isReducedMotion();
        Motion.setReducedMotion(true);
        frame = new JFrame();
        // pack() gives the frame a peer so the root pane lays out; without it the layered pane
        // has zero width and every position assertion below is meaningless.
        frame.pack();
        frame.setSize(900, 600);
        frame.validate();
    }

    @After
    public void tearDown() {
        Motion.setReducedMotion(originalReducedMotion);
        frame.dispose();
    }

    private SidePanel open(SidePanel.Side side) {
        return SidePanel.open(frame, side, 420, "Test panel",
            panel -> new JLabel("body"));
    }

    @Test
    public void openingPutsThePanelInTheLayeredPane() {
        SidePanel panel = open(SidePanel.Side.LEFT);

        assertNotNull(panel);
        assertTrue(panel.isOpen());
        assertSame(frame.getRootPane().getLayeredPane(), panel.getParent());
    }

    @Test
    public void dismissingRemovesIt() {
        SidePanel panel = open(SidePanel.Side.RIGHT);

        panel.dismiss();

        assertFalse(panel.isOpen());
        assertNotNull("the frame should survive its panel", frame.getRootPane());
    }

    @Test
    public void dismissingTwiceIsHarmless() {
        SidePanel panel = open(SidePanel.Side.LEFT);

        panel.dismiss();
        panel.dismiss();

        assertFalse(panel.isOpen());
    }

    /** A right-hand panel starts off the right edge; a left-hand one off the left. */
    @Test
    public void aPanelEndsFlushAgainstItsOwnSide() {
        SidePanel left = open(SidePanel.Side.LEFT);
        assertEquals(0, left.getX());
        left.dismiss();

        SidePanel right = open(SidePanel.Side.RIGHT);
        assertEquals(frame.getRootPane().getLayeredPane().getWidth() - 420, right.getX());
    }

    @Test
    public void theContentFactoryReceivesItsHost() {
        final SidePanel[] seen = new SidePanel[1];
        SidePanel panel = SidePanel.open(frame, SidePanel.Side.LEFT, 420, "Test panel",
            host -> {
                seen[0] = host;
                return new JLabel("body");
            });

        assertSame("content must be able to dismiss its own host", panel, seen[0]);
    }
}
