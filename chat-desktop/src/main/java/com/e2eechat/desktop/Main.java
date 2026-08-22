package com.e2eechat.desktop;

import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class Main {
    public static void main(String[] args) {
        DatabaseHelper.initializeDatabase();
        
        String clientId = args.length > 0 ? args[0] : "alice";
        String receiverId = args.length > 1 ? args[1] : "bob";
        
        String host = "localhost";
        int port = 8080;
        
        // Load properties from ~/.tetherless/config.properties
        File configDir = new File(System.getProperty("user.home"), ".tetherless");
        File configFile = new File(configDir, "config.properties");
        
        if (configFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
                host = props.getProperty("host", host);
                String portStr = props.getProperty("port");
                if (portStr != null) {
                    port = Integer.parseInt(portStr);
                }
            } catch (Exception e) {
                System.err.println("Failed to load config properties: " + e.getMessage());
            }
        }
        
        // CLI args take precedence for host/port (e.g. arg[2] = host, arg[3] = port)
        if (args.length > 2) host = args[2];
        if (args.length > 3) {
            try {
                port = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {}
        }
        
        final String finalHost = host;
        final int finalPort = port;

        ChatClient client = new ChatClient(clientId, receiverId);
        
        SwingUtilities.invokeLater(() -> {
            // 1. Construct the window
            ChatWindow window = new ChatWindow(client);
            
            // 2. Setup graceful shutdown
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    client.disconnect();
                }
            });
            
            // 3. Show window
            window.setVisible(true);
            
            // 4. Connect AFTER UI is fully initialized and listener is registered
            new Thread(() -> {
                client.connect(finalHost, finalPort);
            }, "Connect-Init-Thread").start();
        });
    }
}
