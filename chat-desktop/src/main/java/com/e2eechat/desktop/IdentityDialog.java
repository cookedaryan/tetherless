package com.e2eechat.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridLayout;

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

    private String displayName = null;
    private char[] passphrase = null;

    public IdentityDialog(JFrame parent, Mode mode) {
        super(parent, titleFor(mode), true);

        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JTextField nameField = new JTextField();
        JPasswordField passField = new JPasswordField();
        JPasswordField confirmField = new JPasswordField();

        boolean asksName = mode != Mode.UNLOCK;

        if (mode == Mode.FIRST_RUN) {
            panel.add(new JLabel("Welcome! It looks like this is your first time."));
            panel.add(new JLabel("Display Name:"));
            panel.add(nameField);
            panel.add(new JLabel("Passphrase (used to secure your identity):"));
            panel.add(passField);
            panel.add(new JLabel("Confirm Passphrase:"));
            panel.add(confirmField);
        } else if (mode == Mode.UNLOCK_NEEDS_NAME) {
            panel.add(new JLabel("Your display name was not saved by an earlier version."));
            panel.add(new JLabel("Set it once and it will be remembered from now on."));
            panel.add(new JLabel("Display Name:"));
            panel.add(nameField);
            panel.add(new JLabel("Passphrase:"));
            panel.add(passField);
        } else {
            panel.add(new JLabel("Enter your passphrase to unlock your identity:"));
            panel.add(passField);
        }

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            if (asksName && !ProfileStore.isValid(nameField.getText())) {
                JOptionPane.showMessageDialog(this,
                        "Display Name cannot be empty (and cannot be just \"@\").");
                return;
            }
            if (mode == Mode.FIRST_RUN) {
                String pass = new String(passField.getPassword());
                String confirm = new String(confirmField.getPassword());
                if (pass.isEmpty() || !pass.equals(confirm)) {
                    JOptionPane.showMessageDialog(this, "Passphrases do not match or are empty.");
                    return;
                }
            } else if (passField.getPassword().length == 0) {
                JOptionPane.showMessageDialog(this, "Passphrase cannot be empty.");
                return;
            }
            if (asksName) {
                this.displayName = ProfileStore.normalize(nameField.getText());
            }
            this.passphrase = passField.getPassword();
            dispose();
        });

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        add(panel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        // Enter activates OK, which is what every other dialog in the app does.
        getRootPane().setDefaultButton(okButton);

        pack();
        setLocationRelativeTo(parent);
    }

    private static String titleFor(Mode mode) {
        return mode == Mode.FIRST_RUN ? "Welcome to E2EE Chat" : "Unlock Identity";
    }

    /** The chosen display name, or null when the dialog did not ask for one. */
    public String getDisplayName() {
        return displayName;
    }

    public char[] getPassphrase() {
        return passphrase;
    }
}
