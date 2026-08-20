package com.e2eechat.desktop;

import javax.swing.JFrame; import javax.swing.JTextArea; import javax.swing.JTextField; import javax.swing.JButton; import javax.swing.JPanel; import javax.swing.JScrollPane; import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

public class ChatWindow extends JFrame {
    private JTextArea chatArea;
    private JTextField messageField;
    private JButton sendButton;
    private ChatClient client;
    
    public ChatWindow(ChatClient client) {
        this.client = client;
        setTitle("E2EE Chat Desktop Client");
        setSize(400, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        add(new JScrollPane(chatArea), BorderLayout.CENTER);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        messageField = new JTextField();
        sendButton = new JButton("Send");
        
        bottomPanel.add(messageField, BorderLayout.CENTER);
        bottomPanel.add(sendButton, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
        
        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
    }
    
    private void sendMessage() {
        String text = messageField.getText();
        if (!text.isEmpty()) {
            client.sendMessage(text);
            appendMessage("Me: " + text);
            messageField.setText("");
        }
    }
    
    public void appendMessage(String message) {
        SwingUtilities.invokeLater(() -> chatArea.append(message + "\n"));
    }
}
