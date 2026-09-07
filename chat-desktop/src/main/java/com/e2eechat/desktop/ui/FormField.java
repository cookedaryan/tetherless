package com.e2eechat.desktop.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * A text or passphrase field with a label that moves out of the way instead of taking a row.
 *
 * <h2>Why a floating label</h2>
 * A separate label above each field costs a row of vertical space and, once the field is filled,
 * stops being read at all. Floating it into the border keeps the field's purpose visible while it
 * is being typed into - which matters most on a passphrase field, where the value itself tells the
 * reader nothing.
 *
 * <h2>Why the focus ring is animated</h2>
 * A border that switches colour instantly reads as a redraw. The same change over 150ms reads as
 * the field responding to you. It is the cheapest possible signal that the application is alive and
 * received the click.
 */
public class FormField extends JPanel {

    private static final int HEIGHT = 58;
    private static final int ARC = 12;
    private static final int PAD_X = 14;

    private final JTextComponent field;
    private final String label;
    private final boolean secret;

    /** 0 = resting, 1 = focused. Animated, and also drives the label position. */
    private float focus;
    /** 0 = label sitting in the field, 1 = label floated up. */
    private float floatUp;
    /** 0 = normal, 1 = showing an error. */
    private float error;

    private Motion.Handle focusAnimation;
    private Motion.Handle floatAnimation;
    private Motion.Handle errorAnimation;

    private IconButton revealButton;
    private boolean revealed;

    public FormField(String label, boolean secret) {
        this.label = label;
        this.secret = secret;

        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(javax.swing.BorderFactory.createEmptyBorder(20, PAD_X, 8, PAD_X));

        field = secret ? new JPasswordField() : new JTextField();
        field.setBorder(null);
        field.setOpaque(false);
        field.setFont(Theme.font(Font.PLAIN, 14.5f));
        if (secret) {
            ((JPasswordField) field).setEchoChar('•');
        }

        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                animateFocus(1f);
                animateFloat(1f);
                clearError();
            }

            @Override
            public void focusLost(FocusEvent e) {
                animateFocus(0f);
                animateFloat(hasText() ? 1f : 0f);
            }
        });

        add(field, BorderLayout.CENTER);

        if (secret) {
            // A passphrase that cannot be checked is a passphrase typed twice as often.
            revealButton = new IconButton(() -> TgIcons.eye(18, revealed), "Show passphrase");
            revealButton.addActionListener(e -> toggleReveal());
            add(revealButton, BorderLayout.EAST);
        }

        applyTheme();
        Theme.follow(this, this::applyTheme);
    }

    private void applyTheme() {
        field.setForeground(Theme.textPrimary());
        field.setCaretColor(Theme.accent());
        field.setSelectionColor(Theme.accent());
        field.setSelectedTextColor(Color.WHITE);
        repaint();
    }

    private void toggleReveal() {
        revealed = !revealed;
        ((JPasswordField) field).setEchoChar(revealed ? (char) 0 : '•');
        revealButton.setToolTipText(revealed ? "Hide passphrase" : "Show passphrase");
        revealButton.repaint();
        field.requestFocusInWindow();
    }

    // ------------------------------------------------------------------ state

    public JTextComponent textComponent() {
        return field;
    }

    public String getText() {
        return secret ? new String(((JPasswordField) field).getPassword()) : field.getText();
    }

    public char[] getPassword() {
        return secret ? ((JPasswordField) field).getPassword() : field.getText().toCharArray();
    }

    private boolean hasText() {
        return field.getDocument().getLength() > 0;
    }

    /** Marks the field as rejected. Cleared as soon as the user focuses it again. */
    public void showError() {
        if (errorAnimation != null) {
            errorAnimation.cancel();
        }
        float from = error;
        errorAnimation = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
            error = Motion.lerp(from, 1f, progress);
            repaint();
        }, null);
        Motion.shakeComponent(this, 7);
    }

    public void clearError() {
        if (error == 0f) {
            return;
        }
        if (errorAnimation != null) {
            errorAnimation.cancel();
        }
        float from = error;
        errorAnimation = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
            error = Motion.lerp(from, 0f, progress);
            repaint();
        }, null);
    }

    @Override
    public void requestFocus() {
        field.requestFocusInWindow();
    }

    /** Puts the caret in this field without the deprecation of overriding requestFocus alone. */
    public void focusField() {
        field.requestFocusInWindow();
    }

    private void animateFocus(float target) {
        if (focusAnimation != null) {
            focusAnimation.cancel();
        }
        float from = focus;
        focusAnimation = Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
            focus = Motion.lerp(from, target, progress);
            repaint();
        }, null);
    }

    private void animateFloat(float target) {
        if (floatUp == target) {
            return;
        }
        if (floatAnimation != null) {
            floatAnimation.cancel();
        }
        float from = floatUp;
        floatAnimation = Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, progress -> {
            floatUp = Motion.lerp(from, target, progress);
            repaint();
        }, null);
    }

    /** Re-floats the label when text is set programmatically rather than typed. */
    public void syncLabel() {
        animateFloat(hasText() || field.hasFocus() ? 1f : 0f);
    }

    // ---------------------------------------------------------------- painting

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g2.setColor(Theme.inputBg());
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, ARC, ARC));

            // Resting hairline, brightening to the accent as focus arrives, to danger on error.
            Color resting = Theme.divider();
            Color active = Motion.lerp(Theme.accent(), Theme.danger(), error);
            Color border = Motion.lerp(resting, active, Math.max(focus, error));
            float thickness = Motion.lerp(1f, 2f, Math.max(focus, error));

            g2.setStroke(new java.awt.BasicStroke(thickness));
            float inset = thickness / 2f;
            g2.setColor(border);
            g2.draw(new RoundRectangle2D.Float(inset, inset,
                    w - thickness, h - thickness, ARC, ARC));

            paintLabel(g2);
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }

    private void paintLabel(Graphics2D g2) {
        float restingSize = 14.5f;
        float floatedSize = 11.5f;
        float size = Motion.lerp(restingSize, floatedSize, floatUp);

        int restingY = (getHeight() + g2.getFontMetrics(Theme.font(Font.PLAIN, restingSize))
                .getAscent()) / 2 - 2;
        int floatedY = 20;
        int y = Math.round(Motion.lerp(restingY, floatedY, floatUp));

        Color resting = Theme.textSecondary();
        Color active = error > 0.5f ? Theme.danger() : Theme.accent();
        g2.setColor(Motion.lerp(resting, active, Math.max(focus, error) * floatUp));
        g2.setFont(Theme.font(Font.PLAIN, size));
        g2.drawString(label, PAD_X, y);
    }

    /** Extra space the caller should leave when stacking these, to keep them from touching. */
    public static int gap() {
        return 12;
    }

    /** Insets a container can use to align other content with the field's text. */
    public static Insets textInsets() {
        return new Insets(0, PAD_X, 0, PAD_X);
    }

    /** Convenience for adding an action to the underlying field, such as Enter to submit. */
    public JComponent inner() {
        return field;
    }
}
