package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.AlphaPanel;
import com.e2eechat.desktop.ui.AuroraBackground;
import com.e2eechat.desktop.ui.Brandmark;
import com.e2eechat.desktop.ui.FormField;
import com.e2eechat.desktop.ui.IconButton;
import com.e2eechat.desktop.ui.Motion;
import com.e2eechat.desktop.ui.PillButton;
import com.e2eechat.desktop.ui.StrengthMeter;
import com.e2eechat.desktop.ui.TgIcons;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.WrappedLabel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;

/**
 * The sign-in surface: identity creation on first run, passphrase unlock afterwards.
 *
 * <h2>Why this is not a form in a dialog</h2>
 * It used to be six {@code JLabel}s and three fields in a {@code GridLayout}, with validation
 * failures raised as modal {@code JOptionPane}s. That is the first thing anyone sees of this
 * application, it looked like a debug harness, and it did not use the theme at all - so the app
 * opened light-on-grey and then switched to the user's dark theme a second later.
 *
 * <p>More than looks: a rejected passphrase threw up a second dialog that had to be dismissed
 * before the field could be corrected, which is two extra interactions on the single action a user
 * performs most often. Errors are now shown in place, and the field that caused them is shaken and
 * refocused, so the correction is one keystroke away.
 *
 * <h2>What is deliberately not here</h2>
 * No "remember my passphrase" and no way to skip it. The passphrase is what derives the database
 * key; an option to store it would mean storing the key beside the data it encrypts.
 */
public class IdentityDialog extends JDialog {

    /** Which fields the dialog collects. */
    public enum Mode {
        /** No identity yet: choose a display name and a new passphrase. */
        FIRST_RUN,
        /** Identity and display name both known: only the passphrase is needed. */
        UNLOCK,
        /**
         * Identity exists but no display name was ever stored - a profile created before the name
         * was persisted. Ask for both so the peer id stops changing between launches.
         */
        UNLOCK_NEEDS_NAME
    }

    private static final int WIDTH = 428;

    private String displayName;
    private char[] passphrase;

    private final Mode mode;
    private final FormField nameField;
    private final FormField passField;
    private final FormField confirmField;
    private final StrengthMeter strength;
    private final PillButton submit;
    private final ErrorLabel errorLabel;

    private Point dragOffset;

    public IdentityDialog(JFrame parent, Mode mode) {
        super(parent, titleFor(mode), true);
        this.mode = mode;

        boolean asksName = mode != Mode.UNLOCK;
        boolean firstRun = mode == Mode.FIRST_RUN;

        setUndecorated(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        AuroraBackground root = new AuroraBackground();
        root.setLayout(new BorderLayout());

        root.add(buildTitleBar(), BorderLayout.NORTH);

        JPanel card = new JPanel();
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(4, 32, 32, 32));

        nameField = new FormField("Display name", false);
        passField = new FormField(firstRun ? "Create a passphrase" : "Passphrase", true);
        confirmField = new FormField("Confirm passphrase", true);
        strength = new StrengthMeter();
        errorLabel = new ErrorLabel();
        submit = new PillButton(firstRun ? "Create identity" : "Unlock", PillButton.Style.PRIMARY);

        int step = 0;
        card.add(stagger(new Mark(), step++));
        card.add(gap(18));
        card.add(stagger(heading(firstRun ? "Welcome to Tetherless" : "Welcome back"), step++));
        card.add(gap(6));
        card.add(stagger(subheading(subtitleFor(mode)), step++));
        card.add(gap(26));

        if (asksName) {
            card.add(stagger(nameField, step++));
            card.add(gap(FormField.gap()));
        }

        card.add(stagger(passField, step++));

        if (firstRun) {
            card.add(gap(10));
            card.add(stagger(strength, step++));
            card.add(gap(FormField.gap()));
            card.add(stagger(confirmField, step++));
            // The meter is the only feedback on a choice the user cannot easily revisit later.
            passField.textComponent().getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    strength.evaluate(passField.getPassword());
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    strength.evaluate(passField.getPassword());
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    strength.evaluate(passField.getPassword());
                }
            });
        }

        card.add(gap(14));
        card.add(errorLabel);
        card.add(gap(10));
        card.add(stagger(fullWidth(submit), step++));

        if (firstRun) {
            card.add(gap(16));
            card.add(stagger(footnote(
                    "Your passphrase encrypts this device's message history. "
                            + "It is never sent anywhere, and it cannot be recovered."), step));
        }

        root.add(card, BorderLayout.CENTER);
        setContentPane(root);

        submit.onClick(this::attemptSubmit);
        // Enter anywhere in the form submits, which is what a two-field dialog should do.
        getRootPane().registerKeyboardAction(e -> attemptSubmit(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> cancel(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setSize(WIDTH, preferredHeight(card));
        setLocationRelativeTo(parent);
        applyRoundedShape();

        SwingUtilities.invokeLater(() -> (asksName ? nameField : passField).focusField());
    }

    private int preferredHeight(JPanel card) {
        return card.getPreferredSize().height + 52;
    }

    /**
     * Rounds the window if the platform can do it.
     *
     * <p>Guarded rather than assumed: per-pixel transparency is unavailable on some X11 setups and
     * under some remote-desktop sessions, where calling this throws and would take the sign-in
     * screen down with it. A square window is a perfectly acceptable fallback.
     */
    private void applyRoundedShape() {
        try {
            GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
            if (device.isWindowTranslucencySupported(
                    GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)) {
                setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 18, 18));
            }
        } catch (Exception ignored) {
            // Square corners it is.
        }
    }

    // ------------------------------------------------------------------ pieces

    private JComponent buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 16, 0, 10));

        IconButton close = new IconButton(() -> TgIcons.close(16), "Close");
        close.addActionListener(e -> cancel());
        bar.add(close, BorderLayout.EAST);

        // Undecorated windows have no title bar to drag, and a window that cannot be moved is
        // genuinely annoying on a multi-monitor setup.
        bar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragOffset = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragOffset = null;
            }
        });
        bar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragOffset != null) {
                    Point onScreen = e.getLocationOnScreen();
                    setLocation(onScreen.x - dragOffset.x, onScreen.y - dragOffset.y);
                }
            }
        });
        return bar;
    }

    /** The app mark, painted rather than laid out so it can animate on entry. */
    private static class Mark extends JComponent {
        private float reveal;

        Mark() {
            Motion.animate(Motion.DELIBERATE, Motion.Easing.EASE_OUT_BACK, progress -> {
                reveal = progress;
                repaint();
            }, null);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(72, 72);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 72);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Brandmark.paint(g2, (getWidth() - 72) / 2, 0, 72, reveal);
            } finally {
                g2.dispose();
            }
        }
    }

    /** An error message that takes up no room until there is one. */
    private static class ErrorLabel extends JComponent {
        private String message = "";
        private float shown;

        void show(String text) {
            message = text;
            Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, progress -> {
                shown = progress;
                revalidate();
                repaint();
            }, null);
        }

        void clear() {
            if (shown == 0f) {
                return;
            }
            float from = shown;
            Motion.animate(Motion.FAST, Motion.Easing.EASE_OUT, progress -> {
                shown = Motion.lerp(from, 0f, progress);
                revalidate();
                repaint();
            }, () -> message = "");
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, Math.round(22 * shown));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, Math.round(22 * shown));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (message.isEmpty() || shown <= 0f) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setComposite(java.awt.AlphaComposite.getInstance(
                        java.awt.AlphaComposite.SRC_OVER, shown));
                g2.setColor(Theme.danger());
                g2.setFont(Theme.font(Font.PLAIN, 12.5f));
                g2.drawString(message, 2, g2.getFontMetrics().getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** Wraps a component so it fades and rises in, a beat after the one before it. */
    private static JComponent stagger(JComponent child, int index) {
        AlphaPanel wrapper = new AlphaPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, child.getMaximumSize().height);
            }

            @Override
            public Dimension getPreferredSize() {
                return child.getPreferredSize();
            }
        };
        wrapper.setLayout(new BorderLayout());
        wrapper.add(child, BorderLayout.CENTER);
        wrapper.setAlignmentX(Component.CENTER_ALIGNMENT);
        wrapper.enterAfter(40 + index * 45, Motion.SLOW, 14f);
        return wrapper;
    }

    private static JComponent gap(int height) {
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(1, height));
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return spacer;
    }

    private static JComponent fullWidth(JComponent child) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getPreferredSize().height));
        holder.add(child, BorderLayout.CENTER);
        return holder;
    }

    private static JComponent heading(String text) {
        JLabel label = new JLabel(text, JLabel.CENTER);
        label.setFont(Theme.font(Font.BOLD, 21f));
        label.setForeground(Theme.textPrimary());
        return label;
    }

    private static JComponent subheading(String text) {
        return new WrappedLabel(text, 13.5f, true);
    }

    private static JComponent footnote(String text) {
        return new WrappedLabel(text, 11.5f, true);
    }

    // -------------------------------------------------------------- behaviour

    private void attemptSubmit() {
        boolean asksName = mode != Mode.UNLOCK;

        if (asksName && !ProfileStore.isValid(nameField.getText())) {
            reject(nameField, "Choose a display name.");
            return;
        }
        if (mode == Mode.FIRST_RUN) {
            char[] pass = passField.getPassword();
            char[] confirm = confirmField.getPassword();
            if (pass.length == 0) {
                reject(passField, "Choose a passphrase.");
                return;
            }
            if (!java.util.Arrays.equals(pass, confirm)) {
                reject(confirmField, "The two passphrases do not match.");
                return;
            }
        } else if (passField.getPassword().length == 0) {
            reject(passField, "Enter your passphrase.");
            return;
        }

        errorLabel.clear();
        if (asksName) {
            this.displayName = ProfileStore.normalize(nameField.getText());
        }
        this.passphrase = passField.getPassword();

        // Unlocking derives a PBKDF2 key at 210,000 iterations, which is not instant by design.
        // Showing the spinner before handing back means the window is not simply frozen while the
        // caller does that work.
        submit.setBusy(true);
        SwingUtilities.invokeLater(this::dispose);
    }

    private void reject(FormField field, String message) {
        errorLabel.show(message);
        field.showError();
        field.focusField();
    }

    /**
     * Shows a failure from a previous attempt, so the caller no longer needs a second dialog.
     *
     * @param message what went wrong, in words a user can act on
     */
    public void showFailure(String message) {
        errorLabel.show(message);
        passField.showError();
    }

    private void cancel() {
        passphrase = null;
        dispose();
    }

    private static String titleFor(Mode mode) {
        return mode == Mode.FIRST_RUN ? "Welcome to Tetherless" : "Unlock Tetherless";
    }

    private static String subtitleFor(Mode mode) {
        switch (mode) {
            case FIRST_RUN:
                return "Pick a name others will see, and a passphrase to protect this device.";
            case UNLOCK_NEEDS_NAME:
                return "An earlier version did not save your display name. Set it once.";
            case UNLOCK:
            default:
                return "Enter your passphrase to unlock your identity.";
        }
    }

    /** The chosen display name, or null when the dialog did not ask for one. */
    public String getDisplayName() {
        return displayName;
    }

    public char[] getPassphrase() {
        return passphrase;
    }
}
