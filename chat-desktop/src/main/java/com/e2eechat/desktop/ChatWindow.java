package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.session.Session;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;

public class ChatWindow extends JFrame implements MessageListener, SessionStateListener {
    private final JTextArea chatArea;
    private final JTextField messageField;
    private final JButton sendButton;
    private final JLabel netStatusLabel;
    private final JLabel sessionStatusLabel;
    private final ChatClient client;
    private boolean isNetworkConnected = false;
    
    public ChatWindow(ChatClient client, String fingerprint) {
        this.client = client;
        setTitle("E2EE Chat - " + client.getClientId());
        setSize(500, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        add(new JScrollPane(chatArea), BorderLayout.CENTER);

        JMenuBar menuBar = new JMenuBar();
        JMenu accountMenu = new JMenu("Account");
        JMenuItem identityItem = new JMenuItem("My Identity...");
        identityItem.addActionListener(e -> {
            JTextArea textArea = new JTextArea(fingerprint);
            textArea.setEditable(false);
            textArea.setLineWrap(true);
            textArea.setOpaque(false);
            textArea.setBorder(null);
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(new JLabel("Your Safety Number (Fingerprint):"), BorderLayout.NORTH);
            panel.add(textArea, BorderLayout.CENTER);
            JOptionPane.showMessageDialog(this, panel, "My Identity", JOptionPane.INFORMATION_MESSAGE);
        });
        accountMenu.add(identityItem);
        
        JMenu chatMenu = new JMenu("Chat");
        JMenuItem startChatItem = new JMenuItem("Start Secure Chat...");
        startChatItem.addActionListener(e -> {
            String peerId = JOptionPane.showInputDialog(this, "Enter Peer ID to start chat:");
            if (peerId != null && !peerId.trim().isEmpty()) {
                peerId = peerId.trim();
                client.startSecureChat(peerId);
                
                chatArea.setText("");
                chatArea.append("--- Starting secure chat with " + peerId + " ---\n");
                
                java.util.List<ChatMessage> history = client.getMessageRepository().getMessages(client.getClientId(), peerId, 50);
                for (ChatMessage msg : history) {
                    chatArea.append(msg.getSender() + ": " + msg.getContent() + "\n");
                }
            }
        });
        chatMenu.add(startChatItem);
        
        menuBar.add(accountMenu);
        menuBar.add(chatMenu);
        setJMenuBar(menuBar);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        
        JPanel inputPanel = new JPanel(new BorderLayout());
        messageField = new JTextField();
        sendButton = new JButton("Send");
        updateInputState(Session.State.IDLE);
        
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        bottomPanel.add(inputPanel, BorderLayout.CENTER);
        
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        netStatusLabel = new JLabel("Net: " + ConnectionState.DISCONNECTED);
        netStatusLabel.setForeground(Color.RED);
        sessionStatusLabel = new JLabel(" | Session: IDLE");
        sessionStatusLabel.setForeground(Color.GRAY);
        statusPanel.add(netStatusLabel);
        statusPanel.add(sessionStatusLabel);
        
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);
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
            netStatusLabel.setText("Net: " + state);
            isNetworkConnected = (state == ConnectionState.CONNECTED);
            if (isNetworkConnected) {
                netStatusLabel.setForeground(new Color(0, 128, 0));
            } else {
                netStatusLabel.setForeground(Color.RED);
            }
            // Input state relies on both network and session
            Session session = client.getSession();
            updateInputState(session != null ? session.getState() : Session.State.IDLE);
        });
    }
    
    @Override
    public void onSessionStateChanged(Session.State state) {
        SwingUtilities.invokeLater(() -> {
            sessionStatusLabel.setText(" | Session: " + state);
            if (state == Session.State.ESTABLISHED) {
                sessionStatusLabel.setForeground(new Color(0, 128, 0));
            } else if (state == Session.State.FAILED || state == Session.State.EXPIRED) {
                sessionStatusLabel.setForeground(Color.RED);
            } else {
                sessionStatusLabel.setForeground(Color.ORANGE);
            }
            updateInputState(state);
        });
    }
    
    private void updateInputState(Session.State sessionState) {
        boolean canSend = isNetworkConnected && (sessionState == Session.State.ESTABLISHED);
        sendButton.setEnabled(canSend);
        messageField.setEnabled(canSend);
    }
}
