package com.e2eechat.desktop;

import com.e2eechat.core.keys.JceKeyStoreManager;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyPair;
import java.util.Properties;

public class Main {
    public static void main(String[] args) {
        DatabaseHelper.initializeDatabase();
        
        String host = "localhost";
        int port = 8080;
        
        // System property allows overriding config dir for running multiple clients on one machine
        String configDirPath = System.getProperty("tetherless.config.dir", 
                new File(System.getProperty("user.home"), ".tetherless").getAbsolutePath());
        File configDir = new File(configDirPath);
        
        File configFile = new File(configDir, "config.properties");
        if (configFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                host = props.getProperty("host", host);
                String portStr = props.getProperty("port");
                if (portStr != null) port = Integer.parseInt(portStr);
            } catch (Exception e) {
                System.err.println("Failed to load config properties: " + e.getMessage());
            }
        }
        
        // CLI args take precedence for host/port
        if (args.length > 0) host = args[0];
        if (args.length > 1) {
            try { port = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }
        
        final String finalHost = host;
        final int finalPort = port;
        
        JceKeyStoreManager keyStoreManager = new JceKeyStoreManager(configDir);
        boolean isFirstRun = !new File(configDir, "identity.p12").exists();

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            
            KeyPair identity = null;
            String displayName = isFirstRun ? null : "Me"; // Ideally loaded from config in the future
            String fingerprint = null;
            
            int attempts = 0;
            while (identity == null) {
                IdentityDialog dialog = new IdentityDialog(null, isFirstRun);
                dialog.setVisible(true);
                
                char[] passphrase = dialog.getPassphrase();
                if (passphrase == null) {
                    System.exit(0); // User cancelled
                }
                if (isFirstRun) {
                    displayName = dialog.getDisplayName();
                }
                
                try {
                    identity = keyStoreManager.loadOrCreateIdentity(passphrase);
                    fingerprint = keyStoreManager.fingerprint(identity.getPublic());
                } catch (Exception e) {
                    attempts++;
                    if (attempts >= 5) {
                        JOptionPane.showMessageDialog(null, "Too many failed attempts. Exiting.");
                        System.exit(1);
                    }
                    try {
                        Thread.sleep(attempts * 1000L); // Increasing delay
                    } catch (InterruptedException ignored) {}
                    
                    JOptionPane.showMessageDialog(null, "Failed to load identity: " + e.getMessage());
                }
            }
            
            // Client ID derived from display name + fingerprint
            String clientId = displayName + "#" + fingerprint.substring(0, 8);
            
            // Receiver ID is hardcoded for now, or you can supply it via CLI.
            // But args[0] and args[1] were host and port above. Let's say receiverId is 'bob'.
            String receiverId = "bob"; 
            
            ChatClient client = new ChatClient(clientId, receiverId);
            
            ChatWindow window = new ChatWindow(client, fingerprint);
            
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    client.disconnect();
                }
            });
            
            window.setVisible(true);
            
            new Thread(() -> {
                client.connect(finalHost, finalPort);
            }, "Connect-Init-Thread").start();
        });
    }
}
