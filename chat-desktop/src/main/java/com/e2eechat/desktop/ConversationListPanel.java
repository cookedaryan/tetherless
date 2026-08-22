package com.e2eechat.desktop;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

public class ConversationListPanel extends JPanel {
    private final DefaultListModel<String> listModel;
    private final JList<String> list;
    private final Consumer<String> onConversationSelected;

    public ConversationListPanel(ChatClient client, Consumer<String> onConversationSelected) {
        this.onConversationSelected = onConversationSelected;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(200, 0));
        
        listModel = new DefaultListModel<>();
        list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = list.getSelectedValue();
                if (selected != null) {
                    onConversationSelected.accept(selected);
                }
            }
        });
        
        JPanel topPanel = new JPanel(new BorderLayout());
        JButton newChatBtn = new JButton("New Chat...");
        newChatBtn.addActionListener(e -> {
            String peerId = JOptionPane.showInputDialog(this, "Enter Peer ID to start chat:");
            if (peerId != null && !peerId.trim().isEmpty()) {
                peerId = peerId.trim();
                if (!listModel.contains(peerId)) {
                    listModel.addElement(peerId);
                }
                list.setSelectedValue(peerId, true);
            }
        });
        topPanel.add(newChatBtn, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);
        
        add(new JScrollPane(list), BorderLayout.CENTER);
        
        // Load initial data in background
        SwingWorker<List<String>, Void> worker = new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return client.getMessageRepository().getKnownPeers(client.getClientId());
            }
            @Override
            protected void done() {
                try {
                    List<String> peers = get();
                    for (String peer : peers) {
                        listModel.addElement(peer);
                    }
                } catch (Exception ignored) {}
            }
        };
        worker.execute();
    }
}
