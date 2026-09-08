package com.e2eechat.desktop.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A sheet that slides in over the window from one edge.
 *
 * <p>All of this was private mechanism inside {@code SettingsPanel}, which meant settings was the
 * only thing that could ever be a panel. Nothing here knows what it contains: it owns the layered
 * pane hosting, the scrim, the slide, Escape, focus return and the header, and the content is
 * whatever the caller builds.
 *
 * <p>Added to the layered pane rather than the content pane so the split-pane layout does not have
 * to change to accommodate it, and so it can be removed cleanly.
 */
public final class SidePanel extends JPanel {

    /** Which edge the panel slides from and rests against. */
    public enum Side { LEFT, RIGHT }

    /** Builds the panel's body. Receives the host so the content can dismiss it. */
    public interface ContentFactory {
        JComponent create(SidePanel panel);
    }

    private final JLayeredPane layers;
    private final Scrim scrim;
    private final Side side;
    private final int width;

    private Component focusBeforeOpening;
    private boolean open;
    private ComponentAdapter resizeListener;
    private Motion.Handle motionHandle = Motion.Handle.COMPLETED;

    private SidePanel(JLayeredPane layers, Side side, int width, String title) {
        this.layers = layers;
        this.side = side;
        this.width = width;
        this.scrim = new Scrim();

        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(Theme.sidebarBg());
        // A hairline against the dimmed content, so the sheet has an edge rather than bleeding
        // into the scrim. It goes on whichever side faces the rest of the window.
        setBorder(side == Side.LEFT
                ? BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.divider())
                : BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.divider()));

        add(buildHeader(title), BorderLayout.NORTH);
    }

    /**
     * Slides a panel in over {@code frame}, dimming what is behind it.
     *
     * @return the panel, or {@code null} if the frame has no root pane to host it
     */
    public static SidePanel open(Frame frame, Side side, int width, String title,
                                 ContentFactory content) {
        if (!(frame instanceof RootPaneContainer)) {
            return null;
        }
        JRootPane rootPane = ((RootPaneContainer) frame).getRootPane();
        JLayeredPane layers = rootPane.getLayeredPane();

        SidePanel panel = new SidePanel(layers, side, width, title);
        panel.add(content.create(panel), BorderLayout.CENTER);
        panel.present();
        return panel;
    }

    // Named present() rather than show(): Component.show() is inherited and public, and a
    // private method with that signature will not compile.
    private void present() {
        focusBeforeOpening = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        open = true;

        Rectangle bounds = layers.getBounds();
        scrim.setBounds(0, 0, bounds.width, bounds.height);
        setBounds(offscreenX(bounds.width), 0, width, bounds.height);

        scrim.onClick(new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });

        layers.add(scrim, JLayeredPane.MODAL_LAYER);
        layers.add(this, JLayeredPane.MODAL_LAYER);

        // Keep it filling the height, and against its edge, if the window is resized while open.
        resizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (getParent() == layers) {
                    scrim.setBounds(0, 0, layers.getWidth(), layers.getHeight());
                    setBounds(restingX(layers.getWidth()), 0, width, layers.getHeight());
                }
            }
        };
        layers.addComponentListener(resizeListener);

        getInputMap(WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-side-panel");
        getActionMap().put("close-side-panel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dismiss();
            }
        });

        motionHandle.cancel();
        final int from = offscreenX(bounds.width);
        final int to = restingX(bounds.width);
        motionHandle = Motion.animate(Motion.SLOW, Motion.Easing.EASE_OUT_QUART, new Motion.Frame() {
            @Override
            public void at(float progress) {
                setLocation(Motion.lerp(from, to, progress), 0);
                scrim.setStrength(progress);
            }
        }, null);
    }

    /** Slides the panel out and removes it. Calling it twice does nothing the second time. */
    public void dismiss() {
        if (!open) {
            return;
        }
        open = false;

        motionHandle.cancel();
        final int from = getX();
        final int to = offscreenX(layers.getWidth());
        motionHandle = Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, new Motion.Frame() {
            @Override
            public void at(float progress) {
                setLocation(Motion.lerp(from, to, progress), 0);
                scrim.setStrength(1f - progress);
            }
        }, new Runnable() {
            @Override
            public void run() {
                layers.remove(SidePanel.this);
                layers.remove(scrim);
                layers.removeComponentListener(resizeListener);
                layers.repaint();
                if (focusBeforeOpening != null) {
                    focusBeforeOpening.requestFocusInWindow();
                }
            }
        });
    }

    public boolean isOpen() {
        return open;
    }

    /** Where the panel sits when fully open. */
    private int restingX(int layerWidth) {
        return side == Side.LEFT ? 0 : layerWidth - width;
    }

    /** Where it sits when fully closed, just outside its own edge. */
    private int offscreenX(int layerWidth) {
        return side == Side.LEFT ? -width : layerWidth;
    }

    private JComponent buildHeader(String title) {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBackground(Theme.headerBg());
        header.setBorder(BorderFactory.createEmptyBorder(14, 8, 14, 14));

        IconButton back = new IconButton(() -> TgIcons.arrowLeft(20), "Close");
        back.addActionListener(e -> dismiss());
        header.add(back, BorderLayout.WEST);

        JLabel label = new JLabel(title);
        label.setFont(Theme.font(Font.BOLD, 17f));
        label.setForeground(Theme.textPrimary());
        header.add(label, BorderLayout.CENTER);

        return header;
    }

    /** The dimmed backdrop. Clicking it closes the sheet, as a sheet should. */
    private static class Scrim extends JComponent {
        private float strength;

        Scrim() {
            setOpaque(false);
            setCursor(Cursor.getDefaultCursor());
        }

        void onClick(final Runnable action) {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    action.run();
                }
            });
        }

        void setStrength(float value) {
            strength = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(new Color(0, 0, 0, Math.round(110 * strength)));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
