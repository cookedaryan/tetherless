package com.e2eechat.desktop.ui;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.util.function.BiConsumer;

/**
 * Telegram's interface glyphs, drawn with Java2D.
 *
 * <p>Deliberately vector rather than text: the previous UI used emoji string literals for the
 * paperclip and send controls, which arrived as {@code "??"} because the source was read with the
 * platform charset. Painted paths cannot be mangled by an encoding, are crisp at any scale, and
 * recolour with the theme for free.
 */
public final class TgIcons {

    private TgIcons() {
    }

    /** Builds an {@link Icon} of the given size that paints {@code painter} in {@code colorFn}'s colour. */
    private static Icon icon(int size, BiConsumer<Graphics2D, Integer> painter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.translate(x, y);
                g2.setColor(c != null ? c.getForeground() : Theme.icon());
                painter.accept(g2, size);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private static void stroke(Graphics2D g2, float width) {
        g2.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    // ----------------------------------------------------------------- glyphs

    /** Paper plane, Telegram's send control. */
    public static Icon send(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            GeneralPath p = new GeneralPath();
            p.moveTo(2.5 * u, 21.5 * u);
            p.lineTo(22 * u, 12 * u);
            p.lineTo(2.5 * u, 2.5 * u);
            p.lineTo(6 * u, 12 * u);
            p.closePath();
            g2.fill(p);
            // The crease that separates the plane's near wing from its body.
            stroke(g2, (float) (1.3 * u));
            g2.draw(new java.awt.geom.Line2D.Double(6 * u, 12 * u, 22 * u, 12 * u));
        });
    }

    /** Paperclip, the attachment control. */
    public static Icon attach(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.7 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(17.5 * u, 8 * u);
            p.lineTo(8.5 * u, 17 * u);
            p.curveTo(6.8 * u, 18.7 * u, 4.3 * u, 18.7 * u, 2.9 * u, 17.2 * u);
            p.curveTo(1.4 * u, 15.8 * u, 1.4 * u, 13.3 * u, 3.1 * u, 11.6 * u);
            p.lineTo(13.8 * u, 0.9 * u);
            p.curveTo(14.9 * u, -0.2 * u, 16.6 * u, -0.2 * u, 17.7 * u, 0.9 * u);
            p.curveTo(18.8 * u, 2.0 * u, 18.8 * u, 3.7 * u, 17.7 * u, 4.8 * u);
            p.lineTo(7.4 * u, 15.1 * u);
            p.curveTo(6.9 * u, 15.6 * u, 6.2 * u, 15.6 * u, 5.8 * u, 15.1 * u);
            p.curveTo(5.3 * u, 14.7 * u, 5.3 * u, 14.0 * u, 5.8 * u, 13.5 * u);
            p.lineTo(14.5 * u, 4.8 * u);
            g2.translate(1.5 * u, 3 * u);
            g2.draw(p);
        });
    }

    /** Smiley face, opens the emoji picker. */
    public static Icon emoji(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.7 * u));
            g2.draw(new Ellipse2D.Double(2 * u, 2 * u, 20 * u, 20 * u));
            double eye = 1.9 * u;
            g2.fill(new Ellipse2D.Double(7.7 * u, 8.2 * u, eye, eye));
            g2.fill(new Ellipse2D.Double(14.4 * u, 8.2 * u, eye, eye));
            GeneralPath smile = new GeneralPath();
            smile.moveTo(7.5 * u, 14.4 * u);
            smile.curveTo(9 * u, 17.4 * u, 15 * u, 17.4 * u, 16.5 * u, 14.4 * u);
            g2.draw(smile);
        });
    }

    public static Icon search(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.9 * u));
            g2.draw(new Ellipse2D.Double(3 * u, 3 * u, 13 * u, 13 * u));
            g2.draw(new java.awt.geom.Line2D.Double(15.5 * u, 15.5 * u, 21 * u, 21 * u));
        });
    }

    /** Three stacked rules — the sidebar's main menu. */
    public static Icon menu(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.9 * u));
            for (int i = 0; i < 3; i++) {
                double y = (6 + i * 6) * u;
                g2.draw(new java.awt.geom.Line2D.Double(3.5 * u, y, 20.5 * u, y));
            }
        });
    }

    /** Three vertical dots — the per-chat overflow menu. */
    public static Icon more(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            double d = 2.6 * u;
            for (int i = 0; i < 3; i++) {
                g2.fill(new Ellipse2D.Double(12 * u - d / 2, (5.5 + i * 5.5) * u, d, d));
            }
        });
    }

    /** Pencil, the "compose new chat" floating button. */
    public static Icon pencil(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            GeneralPath nib = new GeneralPath();
            nib.moveTo(3 * u, 17.2 * u);
            nib.lineTo(14.1 * u, 6.1 * u);
            nib.lineTo(17.9 * u, 9.9 * u);
            nib.lineTo(6.8 * u, 21 * u);
            nib.lineTo(2 * u, 22 * u);
            nib.closePath();
            g2.fill(nib);
            GeneralPath cap = new GeneralPath();
            cap.moveTo(15.7 * u, 4.5 * u);
            cap.lineTo(19.5 * u, 8.3 * u);
            cap.lineTo(21.4 * u, 6.4 * u);
            cap.curveTo(22.5 * u, 5.3 * u, 22.5 * u, 3.7 * u, 21.4 * u, 2.6 * u);
            cap.curveTo(20.3 * u, 1.5 * u, 18.7 * u, 1.5 * u, 17.6 * u, 2.6 * u);
            cap.closePath();
            g2.fill(cap);
        });
    }

    /** Single tick: the relay accepted the message. */
    public static Icon check(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.6 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(2 * u, 8.5 * u);
            p.lineTo(6 * u, 12.5 * u);
            p.lineTo(14 * u, 3.5 * u);
            g2.draw(p);
        });
    }

    /** Double tick: the peer's client acknowledged, or has read, the message. */
    public static Icon doubleCheck(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.6 * u));
            GeneralPath first = new GeneralPath();
            first.moveTo(1 * u, 8.5 * u);
            first.lineTo(5 * u, 12.5 * u);
            first.lineTo(12.5 * u, 3.5 * u);
            g2.draw(first);
            GeneralPath second = new GeneralPath();
            second.moveTo(7.5 * u, 11.6 * u);
            second.lineTo(8.8 * u, 12.9 * u);
            second.lineTo(16 * u, 4 * u);
            g2.draw(second);
        });
    }

    /** Clock: queued locally, not yet handed to the relay. */
    public static Icon clock(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.3 * u));
            g2.draw(new Ellipse2D.Double(1.5 * u, 1.5 * u, 13 * u, 13 * u));
            g2.draw(new java.awt.geom.Line2D.Double(8 * u, 4.5 * u, 8 * u, 8.2 * u));
            g2.draw(new java.awt.geom.Line2D.Double(8 * u, 8.2 * u, 11 * u, 9.8 * u));
        });
    }

    /** Exclamation in a circle: the send failed. */
    public static Icon failed(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.4 * u));
            g2.draw(new Ellipse2D.Double(1.5 * u, 1.5 * u, 13 * u, 13 * u));
            g2.draw(new java.awt.geom.Line2D.Double(8 * u, 4.3 * u, 8 * u, 9 * u));
            g2.fill(new Ellipse2D.Double(7.2 * u, 10.6 * u, 1.7 * u, 1.7 * u));
        });
    }

    /** Padlock, shown beside the peer name once the session is established. */
    public static Icon lock(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            GeneralPath shackle = new GeneralPath();
            shackle.moveTo(4.5 * u, 7 * u);
            shackle.lineTo(4.5 * u, 4.8 * u);
            shackle.curveTo(4.5 * u, 2.7 * u, 6.1 * u, 1.2 * u, 8 * u, 1.2 * u);
            shackle.curveTo(9.9 * u, 1.2 * u, 11.5 * u, 2.7 * u, 11.5 * u, 4.8 * u);
            shackle.lineTo(11.5 * u, 7 * u);
            g2.draw(shackle);
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(
                    3 * u, 7 * u, 10 * u, 7.5 * u, 2 * u, 2 * u));
        });
    }

    public static Icon close(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.9 * u));
            g2.draw(new java.awt.geom.Line2D.Double(5.5 * u, 5.5 * u, 18.5 * u, 18.5 * u));
            g2.draw(new java.awt.geom.Line2D.Double(18.5 * u, 5.5 * u, 5.5 * u, 18.5 * u));
        });
    }

    /** Curved arrow, marks the reply action and the reply quote. */
    public static Icon reply(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.8 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(9 * u, 6 * u);
            p.lineTo(3 * u, 11.5 * u);
            p.lineTo(9 * u, 17 * u);
            g2.draw(p);
            GeneralPath curve = new GeneralPath();
            curve.moveTo(3 * u, 11.5 * u);
            curve.lineTo(14 * u, 11.5 * u);
            curve.curveTo(19 * u, 11.5 * u, 21 * u, 14 * u, 21 * u, 19 * u);
            g2.draw(curve);
        });
    }

    /** Chevron pointing down — the jump-to-latest button. */
    public static Icon chevronDown(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (2.1 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(6 * u, 9.5 * u);
            p.lineTo(12 * u, 15.5 * u);
            p.lineTo(18 * u, 9.5 * u);
            g2.draw(p);
        });
    }

    /** Crescent (light mode active) or sun (dark mode active). */
    public static Icon themeToggle(int size, boolean currentlyDark) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            if (currentlyDark) {
                g2.fill(new Ellipse2D.Double(7.5 * u, 7.5 * u, 9 * u, 9 * u));
                stroke(g2, (float) (1.7 * u));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    double cx = 12 * u;
                    double cy = 12 * u;
                    g2.draw(new java.awt.geom.Line2D.Double(
                            cx + Math.cos(a) * 7.2 * u, cy + Math.sin(a) * 7.2 * u,
                            cx + Math.cos(a) * 10 * u, cy + Math.sin(a) * 10 * u));
                }
            } else {
                Path2D moon = new Path2D.Double();
                moon.moveTo(20 * u, 14.8 * u);
                moon.curveTo(18.9 * u, 15.3 * u, 17.7 * u, 15.6 * u, 16.4 * u, 15.6 * u);
                moon.curveTo(11.6 * u, 15.6 * u, 7.7 * u, 11.7 * u, 7.7 * u, 6.9 * u);
                moon.curveTo(7.7 * u, 5.6 * u, 8 * u, 4.4 * u, 8.5 * u, 3.3 * u);
                moon.curveTo(5 * u, 4.9 * u, 2.6 * u, 8.4 * u, 2.6 * u, 12.5 * u);
                moon.curveTo(2.6 * u, 18.1 * u, 7.1 * u, 22.6 * u, 12.7 * u, 22.6 * u);
                moon.curveTo(16.2 * u, 22.6 * u, 19.3 * u, 20.8 * u, 20 * u, 14.8 * u);
                moon.closePath();
                g2.fill(moon);
            }
        });
    }

    /** Shield with a tick — opens the safety-number panel. */
    public static Icon shield(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 24.0;
            stroke(g2, (float) (1.7 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(12 * u, 2 * u);
            p.lineTo(20 * u, 5.2 * u);
            p.lineTo(20 * u, 11.5 * u);
            p.curveTo(20 * u, 16.6 * u, 16.6 * u, 20.6 * u, 12 * u, 22 * u);
            p.curveTo(7.4 * u, 20.6 * u, 4 * u, 16.6 * u, 4 * u, 11.5 * u);
            p.lineTo(4 * u, 5.2 * u);
            p.closePath();
            g2.draw(p);
            GeneralPath tick = new GeneralPath();
            tick.moveTo(8.4 * u, 11.8 * u);
            tick.lineTo(11 * u, 14.4 * u);
            tick.lineTo(15.8 * u, 9 * u);
            g2.draw(tick);
        });
    }

    /**
     * Recolours an icon. {@link Icon} has no colour of its own — the painter reads
     * {@code component.getForeground()} — so this wraps one in a fixed colour for contexts such as
     * bubble ticks where there is no host component to inherit from.
     */
    public static Icon tinted(Icon base, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                base.paintIcon(new java.awt.Canvas() {
                    @Override
                    public Color getForeground() {
                        return color;
                    }
                }, g, x, y);
            }

            @Override
            public int getIconWidth() {
                return base.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return base.getIconHeight();
            }
        };
    }

    // ------------------------------------------------- glyphs for the redesign

    /** Eye, with a stroke through it when the secret is currently visible. */
    public static Icon eye(int size, boolean revealed) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            GeneralPath lid = new GeneralPath();
            lid.moveTo(1.5 * u, 8 * u);
            lid.quadTo(8 * u, 1.5 * u, 14.5 * u, 8 * u);
            lid.quadTo(8 * u, 14.5 * u, 1.5 * u, 8 * u);
            lid.closePath();
            g2.draw(lid);
            g2.draw(new Ellipse2D.Double(6 * u, 6 * u, 4 * u, 4 * u));
            if (revealed) {
                g2.draw(new java.awt.geom.Line2D.Double(3 * u, 13 * u, 13 * u, 3 * u));
            }
        });
    }

    /** Gear. */
    public static Icon settings(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            double cx = 8 * u;
            double cy = 8 * u;
            g2.draw(new Ellipse2D.Double(cx - 2.4 * u, cy - 2.4 * u, 4.8 * u, 4.8 * u));
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2 * i / 8.0;
                double inner = 4.2 * u;
                double outer = 6.4 * u;
                g2.draw(new java.awt.geom.Line2D.Double(
                        cx + Math.cos(angle) * inner, cy + Math.sin(angle) * inner,
                        cx + Math.cos(angle) * outer, cy + Math.sin(angle) * outer));
            }
        });
    }

    /** Head and shoulders. */
    public static Icon person(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            g2.draw(new Ellipse2D.Double(5.2 * u, 2.2 * u, 5.6 * u, 5.6 * u));
            GeneralPath body = new GeneralPath();
            body.moveTo(2.5 * u, 14 * u);
            body.quadTo(8 * u, 8.8 * u, 13.5 * u, 14 * u);
            g2.draw(body);
        });
    }

    /** Artist's palette, for the appearance section. */
    public static Icon palette(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(8 * u, 1.8 * u);
            p.curveTo(12.6 * u, 1.8 * u, 14.4 * u, 5 * u, 14.2 * u, 8 * u);
            p.curveTo(14 * u, 10.6 * u, 11.6 * u, 10.2 * u, 10.6 * u, 11.2 * u);
            p.curveTo(9.8 * u, 12.2 * u, 10.8 * u, 14.2 * u, 8 * u, 14.2 * u);
            p.curveTo(4.4 * u, 14.2 * u, 1.8 * u, 11.4 * u, 1.8 * u, 8 * u);
            p.curveTo(1.8 * u, 4.6 * u, 4.4 * u, 1.8 * u, 8 * u, 1.8 * u);
            p.closePath();
            g2.draw(p);
            g2.fill(new Ellipse2D.Double(5 * u, 5 * u, 1.7 * u, 1.7 * u));
            g2.fill(new Ellipse2D.Double(9 * u, 4.2 * u, 1.7 * u, 1.7 * u));
            g2.fill(new Ellipse2D.Double(4 * u, 9 * u, 1.7 * u, 1.7 * u));
        });
    }

    /** Key, for the security section. */
    public static Icon key(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            g2.draw(new Ellipse2D.Double(2 * u, 5 * u, 6 * u, 6 * u));
            g2.draw(new java.awt.geom.Line2D.Double(7.6 * u, 8 * u, 14 * u, 8 * u));
            g2.draw(new java.awt.geom.Line2D.Double(11.5 * u, 8 * u, 11.5 * u, 10.6 * u));
            g2.draw(new java.awt.geom.Line2D.Double(13.6 * u, 8 * u, 13.6 * u, 11.4 * u));
        });
    }

    /** Circled "i". */
    public static Icon info(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            g2.draw(new Ellipse2D.Double(1.8 * u, 1.8 * u, 12.4 * u, 12.4 * u));
            g2.draw(new java.awt.geom.Line2D.Double(8 * u, 7.2 * u, 8 * u, 11.4 * u));
            g2.fill(new Ellipse2D.Double(7.25 * u, 4.3 * u, 1.5 * u, 1.5 * u));
        });
    }

    /** Plug, for the connection section. */
    public static Icon plug(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.5 * u));
            g2.draw(new java.awt.geom.Line2D.Double(6 * u, 1.8 * u, 6 * u, 5 * u));
            g2.draw(new java.awt.geom.Line2D.Double(10 * u, 1.8 * u, 10 * u, 5 * u));
            GeneralPath body = new GeneralPath();
            body.moveTo(3.6 * u, 5 * u);
            body.lineTo(12.4 * u, 5 * u);
            body.lineTo(12.4 * u, 8 * u);
            body.curveTo(12.4 * u, 11 * u, 9.8 * u, 11.6 * u, 9.8 * u, 11.6 * u);
            body.lineTo(9.8 * u, 14.2 * u);
            body.lineTo(6.2 * u, 14.2 * u);
            body.lineTo(6.2 * u, 11.6 * u);
            body.curveTo(6.2 * u, 11.6 * u, 3.6 * u, 11 * u, 3.6 * u, 8 * u);
            body.closePath();
            g2.draw(body);
        });
    }

    public static Icon chevronRight(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.7 * u));
            GeneralPath p = new GeneralPath();
            p.moveTo(6 * u, 3.5 * u);
            p.lineTo(10.5 * u, 8 * u);
            p.lineTo(6 * u, 12.5 * u);
            g2.draw(p);
        });
    }

    public static Icon arrowLeft(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.7 * u));
            g2.draw(new java.awt.geom.Line2D.Double(13 * u, 8 * u, 3 * u, 8 * u));
            GeneralPath head = new GeneralPath();
            head.moveTo(7.5 * u, 3.5 * u);
            head.lineTo(3 * u, 8 * u);
            head.lineTo(7.5 * u, 12.5 * u);
            g2.draw(head);
        });
    }

    public static Icon plus(int size) {
        return icon(size, (g2, s) -> {
            double u = s / 16.0;
            stroke(g2, (float) (1.8 * u));
            g2.draw(new java.awt.geom.Line2D.Double(8 * u, 3 * u, 8 * u, 13 * u));
            g2.draw(new java.awt.geom.Line2D.Double(3 * u, 8 * u, 13 * u, 8 * u));
        });
    }
}
