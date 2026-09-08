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
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel host, on its own.
 *
 * <p>Reduced motion is switched on throughout: {@code Motion.animate} then lands on the end state
 * and runs its completion handler immediately, so opening and dismissing are synchronous and a
 * test does not have to wait on an animation.
 *
 * <p>{@code Motion} documents itself as not thread-safe, and once {@link JFrame#pack()} has given
 * the frame a peer, Swing's contract requires the event dispatch thread for any further
 * construction, mutation or query. Every such step below runs through {@link #onEdt(Runnable)}.
 */
public class SidePanelTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    private JFrame frame;
    private boolean originalReducedMotion;

    @Before
    public void setUp() throws Exception {
        onEdt(() -> {
            originalReducedMotion = Motion.isReducedMotion();
            Motion.setReducedMotion(true);
            frame = new JFrame();
            // pack() gives the frame a peer so the root pane lays out; without it the layered pane
            // has zero width and every position assertion below is meaningless.
            frame.pack();
            frame.setSize(900, 600);
            frame.validate();
        });
    }

    @After
    public void tearDown() throws Exception {
        onEdt(() -> {
            Motion.setReducedMotion(originalReducedMotion);
            frame.dispose();
        });
    }

    private SidePanel open(SidePanel.Side side) {
        return SidePanel.open(frame, side, 420, "Test panel",
            panel -> new JLabel("body"));
    }

    /**
     * Runs {@code action} on the event dispatch thread and waits for it to finish, rethrowing
     * whatever it threw so a failing assertion still fails the test cleanly.
     */
    private static void onEdt(Runnable action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    @Test
    public void openingPutsThePanelInTheLayeredPane() throws Exception {
        final boolean[] isOpen = new boolean[1];
        final Object[] parent = new Object[1];
        final Object[] layeredPane = new Object[1];
        final SidePanel[] panelRef = new SidePanel[1];

        onEdt(() -> {
            SidePanel panel = open(SidePanel.Side.LEFT);
            panelRef[0] = panel;
            isOpen[0] = panel != null && panel.isOpen();
            parent[0] = panel == null ? null : panel.getParent();
            layeredPane[0] = frame.getRootPane().getLayeredPane();
        });

        assertNotNull(panelRef[0]);
        assertTrue(isOpen[0]);
        assertSame(layeredPane[0], parent[0]);
    }

    @Test
    public void dismissingRemovesIt() throws Exception {
        final int[] componentCountBeforeOpen = new int[1];
        final int[] listenerCountBeforeOpen = new int[1];
        final boolean[] isOpenAfterDismiss = new boolean[1];
        final Object[] parentAfterDismiss = new Object[1];
        final int[] componentCountAfterDismiss = new int[1];
        final int[] listenerCountAfterDismiss = new int[1];

        onEdt(() -> {
            JLayeredPane layers = frame.getRootPane().getLayeredPane();
            componentCountBeforeOpen[0] = layers.getComponentCount();
            listenerCountBeforeOpen[0] = layers.getComponentListeners().length;

            SidePanel panel = open(SidePanel.Side.RIGHT);
            panel.dismiss();

            isOpenAfterDismiss[0] = panel.isOpen();
            parentAfterDismiss[0] = panel.getParent();
            componentCountAfterDismiss[0] = layers.getComponentCount();
            listenerCountAfterDismiss[0] = layers.getComponentListeners().length;
        });

        assertFalse(isOpenAfterDismiss[0]);
        assertNull("the panel should be detached from its host", parentAfterDismiss[0]);
        assertEquals("the scrim should be removed along with the panel",
                componentCountBeforeOpen[0], componentCountAfterDismiss[0]);
        assertEquals("the resize listener should be removed along with the panel",
                listenerCountBeforeOpen[0], listenerCountAfterDismiss[0]);
    }

    @Test
    public void dismissingTwiceIsHarmless() throws Exception {
        final boolean[] isOpenAfter = new boolean[1];

        onEdt(() -> {
            SidePanel panel = open(SidePanel.Side.LEFT);
            panel.dismiss();
            panel.dismiss();
            isOpenAfter[0] = panel.isOpen();
        });

        assertFalse(isOpenAfter[0]);
    }

    /** A right-hand panel starts off the right edge; a left-hand one off the left. */
    @Test
    public void aPanelEndsFlushAgainstItsOwnSide() throws Exception {
        final int[] leftX = new int[1];
        final int[] rightX = new int[1];
        final int[] expectedRightX = new int[1];

        onEdt(() -> {
            SidePanel left = open(SidePanel.Side.LEFT);
            leftX[0] = left.getX();
            left.dismiss();

            SidePanel right = open(SidePanel.Side.RIGHT);
            rightX[0] = right.getX();
            expectedRightX[0] = frame.getRootPane().getLayeredPane().getWidth() - 420;
        });

        assertEquals(0, leftX[0]);
        assertEquals(expectedRightX[0], rightX[0]);
    }

    @Test
    public void theContentFactoryReceivesItsHost() throws Exception {
        final SidePanel[] panelRef = new SidePanel[1];
        final SidePanel[] seen = new SidePanel[1];

        onEdt(() -> {
            SidePanel panel = SidePanel.open(frame, SidePanel.Side.LEFT, 420, "Test panel",
                host -> {
                    seen[0] = host;
                    return new JLabel("body");
                });
            panelRef[0] = panel;
        });

        assertSame("content must be able to dismiss its own host", panelRef[0], seen[0]);
    }
}
