package com.e2eechat.desktop;

import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        DatabaseHelper.initializeDatabase();
        String clientId = args.length > 0 ? args[0] : "user1";
        String receiverId = args.length > 1 ? args[1] : "user2";
        
        ChatClient client = new ChatClient(clientId, receiverId);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            client.disconnect();
        }));
        
        client.connect("localhost", 8080);
        
        SwingUtilities.invokeLater(() -> {
            ChatWindow window = new ChatWindow(client);
            client.setWindow(window);
            window.setVisible(true);
        });
    }
}
