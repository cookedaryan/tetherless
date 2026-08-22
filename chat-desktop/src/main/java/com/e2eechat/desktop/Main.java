package com.e2eechat.desktop;

import com.e2eechat.core.keys.JceKeyStoreManager;
import com.e2eechat.core.session.SessionManager;

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
            String displayName = isFirstRun ? null : "Me";
            String fingerprint = null;
            
            int attempts = 0;
            while (identity == null) {
                IdentityDialog dialog = new IdentityDialog(null, isFirstRun);
                dialog.setVisible(true);
                
                char[] passphrase = dialog.getPassphrase();
                if (passphrase == null) {
                    System.exit(0);
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
                        Thread.sleep(attempts * 1000L);
                    } catch (InterruptedException ignored) {}
                    
                    JOptionPane.showMessageDialog(null, "Failed to load identity: " + e.getMessage());
                }
            }
            
            // Client ID derived from display name + fingerprint
            String clientId = displayName + "#" + fingerprint.substring(0, 8);
            
            // Create SessionManager. Note: For v1.0, peerKeyLookup is deferred to CORE-06.
            // For now, we will assume a dummy trust model or mock it to accept signatures if possible.
            // Wait, SignatureVerifier REQUIRES a valid public key to verify signatures!
            // Without CORE-06, we don't have a way to securely lookup peer public keys.
            // We need to fetch the peer's public key from somewhere, maybe exchange it in a plaintext HELLO?
            // But the plan says "PeerKeyLookup will just return a dummy key or prompt the user, since CORE-06 is deferred".
            // Actually, we must use the remote user's identity key! But how do we know it?
            // If we don't have it, we can't verify the signature.
            // Since we can't fetch it dynamically without CORE-06, we will bypass identity verification just for this step to see the flow work,
            // or we just return a key we know (which defeats the purpose).
            // Let's create an "insecure mode" lookup that returns null, and SignatureVerifier drops it...
            // Oh, wait! The user prompt says: "PeerKeyLookup will just return a dummy key or prompt the user... bypass strictly pinned keys".
            // If it returns null, SignatureVerifier will return NO_KEY_FOR_SENDER.
            // Wait! I can't bypass SignatureVerifier unless I modify SessionManager.
            // Let's modify SessionManager to accept NO_KEY_FOR_SENDER just for this phase, OR we can inject a dummy key that SignatureVerifier will fail on.
            // Wait! The easiest way is to use our own key as the peer key in testing, but that won't work across two clients.
            // Actually, the Message doesn't include the sender's public identity key, only the senderId.
            // I'll leave the peerKeyLookup returning null for now, and I'll modify SessionManager to temporarily allow NO_KEY_FOR_SENDER in this phase (or we can just skip verifying if no key is present for testing).
            
            SessionManager sessionManager = new SessionManager(clientId, senderId -> null); 
            
            // Wait, we need to pass identity to ChatClient
            ChatClient client = new ChatClient(clientId, identity, sessionManager);
            
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
