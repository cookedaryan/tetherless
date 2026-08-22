package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.session.Session;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ChatWindow extends JFrame implements MessageListener, SessionStateListener {

    private final ChatClient client;
    
    private final JList<ChatMessage> chatList;
    private final DefaultListModel<ChatMessage> chatModel;
    private final JTextField inputField;
    private final JButton sendButton;
    private final JLabel headerLabel;
    private final JPanel rightPanel;

    public ChatWindow(ChatClient client, String fingerprint) {
        this.client = client;
        
        setTitle("E2EE Chat - " + client.getClientId());
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        
        ConversationListPanel leftPanel = new ConversationListPanel(client, this::onConversationSelected);
        splitPane.setLeftComponent(leftPanel);
        
        rightPanel = new JPanel(new BorderLayout());
        
        headerLabel = new JLabel("Select a conversation to start");
        headerLabel.setOpaque(true);
        headerLabel.setBackground(Color.LIGHT_GRAY);
        headerLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        headerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        rightPanel.add(headerLabel, BorderLayout.NORTH);
        
        chatModel = new DefaultListModel<>();
        chatList = new JList<>(chatModel);
        chatList.setCellRenderer(new MessageBubbleRenderer(client.getClientId()));
        chatList.setBackground(new Color(240, 240, 240));
        
        rightPanel.add(new JScrollPane(chatList), BorderLayout.CENTER);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputField.setEnabled(false);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        
        sendButton = new JButton("Send");
        sendButton.setEnabled(false);
        bottomPanel.add(sendButton, BorderLayout.EAST);
        
        rightPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        splitPane.setRightComponent(rightPanel);
        add(splitPane);
        
        Action sendAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String text = inputField.getText().trim();
                if (!text.isEmpty()) {
                    client.sendMessage(text);
                    inputField.setText("");
                    
                    // Add local message immediately
                    chatModel.addElement(new ChatMessage(client.getClientId(), client.getReceiverId(), text, System.currentTimeMillis()));
                    scrollToBottom();
                }
            }
        };
        inputField.addActionListener(sendAction);
        sendButton.addActionListener(sendAction);
        
        client.addMessageListener(this);
    }
    
    private void onConversationSelected(String peerId) {
        client.startSecureChat(peerId); // Initiates or resumes Handshake
        
        headerLabel.setText("Connecting to " + peerId + "...");
        headerLabel.setBackground(Color.LIGHT_GRAY);
        inputField.setEnabled(false);
        sendButton.setEnabled(false);
        
        chatModel.clear();
        
        SwingWorker<List<ChatMessage>, Void> worker = new SwingWorker<List<ChatMessage>, Void>() {
            @Override
            protected List<ChatMessage> doInBackground() {
                return client.getMessageRepository().getMessages(client.getClientId(), peerId, 50);
            }
            @Override
            protected void done() {
                try {
                    List<ChatMessage> history = get();
                    for (ChatMessage m : history) {
                        chatModel.addElement(m);
                    }
                    scrollToBottom();
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }
    
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            int lastIndex = chatList.getModel().getSize() - 1;
            if (lastIndex >= 0) {
                chatList.ensureIndexIsVisible(lastIndex);
            }
        });
    }

    @Override
    public void onMessageReceived(Message msg) {
        SwingUtilities.invokeLater(() -> {
            if (msg.getType() == MessageType.ERROR) {
                String error = new String(msg.getPayload(), StandardCharsets.UTF_8);
                if (error.contains("SECURITY ALERT")) {
                    headerLabel.setText(error);
                    headerLabel.setBackground(Color.RED);
                    headerLabel.setForeground(Color.WHITE);
                    inputField.setEnabled(false);
                    sendButton.setEnabled(false);
                }
                return;
            }
            
            if (msg.getType() == MessageType.TEXT_MESSAGE) {
                if (msg.getSenderId().equals(client.getReceiverId())) {
                    String text = new String(msg.getPayload(), StandardCharsets.UTF_8);
                    chatModel.addElement(new ChatMessage(msg.getSenderId(), client.getClientId(), text, msg.getTimestamp()));
                    scrollToBottom();
                }
            }
        });
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state) {
        SwingUtilities.invokeLater(() -> {
            if (state == ConnectionState.CONNECTED) {
                // Connection is established. But session is per peer.
            } else {
                headerLabel.setText("Disconnected from server");
                headerLabel.setBackground(Color.ORANGE);
                inputField.setEnabled(false);
                sendButton.setEnabled(false);
            }
        });
    }

    @Override
    public void onSessionStateChanged(Session.State state) {
        SwingUtilities.invokeLater(() -> {
            String peerId = client.getReceiverId();
            if (peerId == null) return;
            
            if (state == Session.State.ESTABLISHED) {
                String fp = client.getPeerFingerprint(peerId);
                if (fp != null) {
                    headerLabel.setText("Verified | Fingerprint: " + fp);
                    headerLabel.setBackground(new Color(220, 248, 198));
                    headerLabel.setForeground(Color.BLACK);
                } else {
                    headerLabel.setText("Connected to " + peerId);
                }
                inputField.setEnabled(true);
                sendButton.setEnabled(true);
            } else {
                headerLabel.setText("Handshaking with " + peerId + "...");
                headerLabel.setBackground(Color.LIGHT_GRAY);
                headerLabel.setForeground(Color.BLACK);
                inputField.setEnabled(false);
                sendButton.setEnabled(false);
            }
        });
    }
}
