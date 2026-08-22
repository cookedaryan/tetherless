package com.e2eechat.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MessageBubbleRenderer extends JPanel implements ListCellRenderer<ChatMessage> {
    private final String localClientId;
    private final JLabel textLabel;
    private final JLabel metaLabel;
    private final JPanel bubblePanel;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

    public MessageBubbleRenderer(String localClientId) {
        this.localClientId = localClientId;
        setLayout(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(5, 10, 5, 10));

        bubblePanel = new JPanel();
        bubblePanel.setLayout(new BoxLayout(bubblePanel, BoxLayout.Y_AXIS));
        bubblePanel.setBorder(new EmptyBorder(8, 12, 8, 12));
        
        textLabel = new JLabel();
        textLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        
        metaLabel = new JLabel();
        metaLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        metaLabel.setForeground(Color.DARK_GRAY);
        
        bubblePanel.add(textLabel);
        bubblePanel.add(Box.createVerticalStrut(4));
        bubblePanel.add(metaLabel);
    }

    @Override
    public Component getListCellRendererComponent(JList<? extends ChatMessage> list, ChatMessage msg, int index, boolean isSelected, boolean cellHasFocus) {
        textLabel.setText(msg.getContent());
        metaLabel.setText(timeFormat.format(new Date(msg.getTimestamp())));
        
        removeAll();
        
        if (msg.getSender().equals(localClientId)) {
            bubblePanel.setBackground(new Color(220, 248, 198)); // Light green for me
            bubblePanel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            textLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            metaLabel.setAlignmentX(Component.RIGHT_ALIGNMENT);
            
            add(bubblePanel, BorderLayout.EAST);
        } else {
            bubblePanel.setBackground(Color.WHITE); // White for them
            bubblePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
            textLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            metaLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            
            add(bubblePanel, BorderLayout.WEST);
        }
        
        if (msg.getContent().contains("[Decryption Failed]")) {
            bubblePanel.setBackground(new Color(255, 200, 200)); // Red for error
        }
        
        return this;
    }
}
