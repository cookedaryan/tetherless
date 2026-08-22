package com.e2eechat.desktop;

import javax.swing.*;
import java.awt.*;
import java.io.File;

public class IdentityDialog extends JDialog {
    private String displayName = null;
    private char[] passphrase = null;
    private boolean isFirstRun;
    
    public IdentityDialog(JFrame parent, boolean isFirstRun) {
        super(parent, isFirstRun ? "Welcome to E2EE Chat" : "Unlock Identity", true);
        this.isFirstRun = isFirstRun;
        
        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JTextField nameField = new JTextField();
        JPasswordField passField = new JPasswordField();
        JPasswordField confirmField = new JPasswordField();
        
        if (isFirstRun) {
            panel.add(new JLabel("Welcome! It looks like this is your first time."));
            panel.add(new JLabel("Display Name:"));
            panel.add(nameField);
            panel.add(new JLabel("Passphrase (used to secure your identity):"));
            panel.add(passField);
            panel.add(new JLabel("Confirm Passphrase:"));
            panel.add(confirmField);
        } else {
            panel.add(new JLabel("Enter your passphrase to unlock your identity:"));
            panel.add(passField);
        }
        
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            if (isFirstRun) {
                if (nameField.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Display Name cannot be empty.");
                    return;
                }
                String pass = new String(passField.getPassword());
                String confirm = new String(confirmField.getPassword());
                if (pass.isEmpty() || !pass.equals(confirm)) {
                    JOptionPane.showMessageDialog(this, "Passphrases do not match or are empty.");
                    return;
                }
                this.displayName = nameField.getText().trim();
                this.passphrase = passField.getPassword();
            } else {
                if (passField.getPassword().length == 0) {
                    JOptionPane.showMessageDialog(this, "Passphrase cannot be empty.");
                    return;
                }
                this.passphrase = passField.getPassword();
            }
            dispose();
        });
        
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        
        JPanel buttonPanel = new JPanel();
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        
        add(panel, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        
        pack();
        setLocationRelativeTo(parent);
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public char[] getPassphrase() {
        return passphrase;
    }
}
