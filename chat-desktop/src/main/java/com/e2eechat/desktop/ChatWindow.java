package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;

public class ChatWindow extends JFrame implements MessageListener {
    private final JTextArea chatArea;
    private final JTextField messageField;
    private final JButton sendButton;
    private final JLabel statusLabel;
    private final ChatClient client;
    
    public ChatWindow(ChatClient client, String fingerprint) {
        this.client = client;
        setTitle("E2EE Chat Desktop Client - " + client.getClientId());
        setSize(500, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        JMenuBar menuBar = new JMenuBar();
        JMenu accountMenu = new JMenu("Account");
        JMenuItem identityItem = new JMenuItem("My Identity...");
        identityItem.addActionListener(e -> {
            JTextArea textArea = new JTextArea(fingerprint);
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setOpaque(false);
            textArea.setBorder(null);
            
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel("Your Safety Number (Fingerprint):"), BorderLayout.NORTH);
            panel.add(textArea, BorderLayout.CENTER);
            
            JOptionPane.showMessageDialog(this, panel, "My Identity", JOptionPane.INFORMATION_MESSAGE);
        });
        accountMenu.add(identityItem);
        menuBar.add(accountMenu);
        setJMenuBar(menuBar);
        
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        add(new JScrollPane(chatArea), BorderLayout.CENTER);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        
        JPanel inputPanel = new JPanel(new BorderLayout());
        messageField = new JTextField();
        sendButton = new JButton("Send");
        sendButton.setEnabled(false); // Disabled until connected
        
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.add(inputPanel, BorderLayout.CENTER);
        
        statusLabel = new JLabel("Status: " + ConnectionState.DISCONNECTED);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        statusLabel.setForeground(Color.GRAY);
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
        
        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        
        client.addMessageListener(this);
    }
    
    private void sendMessage() {
        if (!sendButton.isEnabled()) return;
        
        String text = messageField.getText().trim();
        if (!text.isEmpty()) {
            client.sendMessage(text);
            appendMessage("Me: " + text);
            messageField.setText("");
        }
    }
    
    private void appendMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(message + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    @Override
    public void onMessageReceived(Message msg) {
        if (msg.getType() == MessageType.TEXT_MESSAGE) {
            String text = new String(msg.getPayload(), StandardCharsets.UTF_8);
            appendMessage(msg.getSenderId() + ": " + text);
        } else if (msg.getType() == MessageType.ERROR) {
            String errorMsg = new String(msg.getPayload(), StandardCharsets.UTF_8);
            appendMessage("[SERVER ERROR]: " + errorMsg);
        }
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: " + state);
            switch (state) {
                case CONNECTED:
                    statusLabel.setForeground(new Color(0, 128, 0));
                    sendButton.setEnabled(true);
                    messageField.setEnabled(true);
                    break;
                case CONNECTING:
                case RECONNECTING:
                    statusLabel.setForeground(Color.ORANGE);
                    sendButton.setEnabled(false);
                    messageField.setEnabled(false);
                    break;
                case DISCONNECTED:
                    statusLabel.setForeground(Color.RED);
                    sendButton.setEnabled(false);
                    messageField.setEnabled(false);
                    break;
            }
        });
    }
}
