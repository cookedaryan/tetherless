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

    /** Null when the sheet has no header. Held so the palette can be re-applied to it. */
    private JPanel header;
    private JLabel headerLabel;

    private SidePanel(JLayeredPane layers, Side side, int width, String title) {
        this.layers = layers;
        this.side = side;
        this.width = width;
        this.scrim = new Scrim();

        setLayout(new BorderLayout());
        setOpaque(true);

        // A null title means the content draws its own top - the navigation drawer leads with a
        // profile block, and a back arrow above it would be a second way to close the same sheet.
        if (title != null) {
            header = buildHeader(title);
            add(header, BorderLayout.NORTH);
        }

        applyTheme();
        // Not addListener: the static listener list outlives the process's windows, and a panel is
        // built afresh on every open. follow() drops the registration when the sheet leaves the
        // layered pane, so dismissing one does not leak it.
        Theme.follow(this, this::applyTheme);
    }

    /**
     * Reads the palette into everything the sheet paints itself.
     *
     * <p>Called again on a theme change because these are not values a look and feel can revise:
     * {@code setBackground} and a matte border hold plain {@code Color}s, so {@code
     * FlatLaf.updateUI()} leaves them exactly as they were. A panel open across a toggle otherwise
     * stays in the old palette - most visibly Settings, whose own Night mode row is inside one.
     */
    private void applyTheme() {
        setBackground(Theme.sidebarBg());
        // A hairline against the dimmed content, so the sheet has an edge rather than bleeding
        // into the scrim. It goes on whichever side faces the rest of the window.
        setBorder(side == Side.LEFT
                ? BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.divider())
                : BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.divider()));
        if (header != null) {
            header.setBackground(Theme.headerBg());
            headerLabel.setForeground(Theme.textPrimary());
        }
        repaint();
    }

    /**
     * Slides a panel in over {@code frame}, dimming what is behind it.
     *
     * @param title the header's label, or {@code null} for no header at all
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
        // Within one layer, position 0 is the top and add() appends to the bottom - so without
        // this the scrim sits over the sheet, dimming it and eating every click it should have
        // received. The scrim closes on click, so the symptom is a panel that looks faded and
        // whose controls silently do nothing.
        layers.setPosition(this, 0);

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

    private JPanel buildHeader(String title) {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(true);
        bar.setBorder(BorderFactory.createEmptyBorder(14, 8, 14, 14));

        IconButton back = new IconButton(() -> TgIcons.arrowLeft(20), "Close");
        back.addActionListener(e -> dismiss());
        bar.add(back, BorderLayout.WEST);

        headerLabel = new JLabel(title);
        headerLabel.setFont(Theme.font(Font.BOLD, 17f));
        bar.add(headerLabel, BorderLayout.CENTER);

        // Colours are left to applyTheme(), which runs now and again on every theme change.
        return bar;
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
