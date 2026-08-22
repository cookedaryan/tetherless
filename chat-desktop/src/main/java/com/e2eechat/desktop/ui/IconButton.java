package com.e2eechat.desktop.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.function.Supplier;

/**
 * Borderless circular icon button with Telegram's hover ripple.
 *
 * <p>Colours are supplied as {@link Supplier}s rather than values so a button re-reads the palette
 * on every repaint. That is what lets the dark-mode toggle take effect without rebuilding the
 * component tree.
 */
public class IconButton extends JButton {

    private final Supplier<Icon> iconSupplier;
    private final Supplier<Color> restColor;
    private final Supplier<Color> hoverColor;
    private boolean hovered;
    private boolean drawHoverCircle = true;

    public IconButton(Supplier<Icon> iconSupplier, String tooltip) {
        this(iconSupplier, tooltip, Theme::icon, Theme::iconHover);
    }

    public IconButton(Supplier<Icon> iconSupplier, String tooltip,
                      Supplier<Color> restColor, Supplier<Color> hoverColor) {
        this.iconSupplier = iconSupplier;
        this.restColor = restColor;
        this.hoverColor = hoverColor;

        setToolTipText(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(new Cursor(Cursor.HAND_CURSOR));
        setBorder(null);

        Icon probe = iconSupplier.get();
        int pad = 14;
        setPreferredSize(new Dimension(probe.getIconWidth() + pad, probe.getIconHeight() + pad));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
    }

    /** Suppresses the circular hover wash, for buttons sitting on an already-tinted surface. */
    public IconButton withoutHoverCircle() {
        this.drawHoverCircle = false;
        return this;
    }

    @Override
    public Color getForeground() {
        // Called by the icon painter to decide the glyph colour.
        //
        // JButton's constructor installs the look and feel, which queries the foreground before
        // this subclass's fields are assigned - the "this escape" the compiler warns about. Fall
        // back to the superclass until the suppliers exist.
        if (restColor == null || hoverColor == null) {
            return super.getForeground();
        }
        if (!isEnabled()) {
            Color c = restColor.get();
            return new Color(c.getRed(), c.getGreen(), c.getBlue(), 90);
        }
        return hovered ? hoverColor.get() : restColor.get();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (hovered && isEnabled() && drawHoverCircle) {
            Color wash = Theme.isDark() ? new Color(255, 255, 255, 20) : new Color(0, 0, 0, 14);
            g2.setColor(wash);
            int d = Math.min(getWidth(), getHeight());
            g2.fill(new Ellipse2D.Double((getWidth() - d) / 2.0, (getHeight() - d) / 2.0, d, d));
        }

        Icon icon = iconSupplier.get();
        int x = (getWidth() - icon.getIconWidth()) / 2;
        int y = (getHeight() - icon.getIconHeight()) / 2;
        icon.paintIcon(this, g2, x, y);

        g2.dispose();
    }
}
