package com.e2eechat.mobile;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MessageAdapter extends ListAdapter<MessageEntity, MessageAdapter.MessageViewHolder> {

    private static final int VIEW_TYPE_IN = 1;
    private static final int VIEW_TYPE_OUT = 2;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public MessageAdapter() {
        super(new DiffUtil.ItemCallback<MessageEntity>() {
            @Override
            public boolean areItemsTheSame(@NonNull MessageEntity oldItem, @NonNull MessageEntity newItem) {
                return oldItem.messageId != null && oldItem.messageId.equals(newItem.messageId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull MessageEntity oldItem, @NonNull MessageEntity newItem) {
                return oldItem.content.equals(newItem.content) && 
                       (oldItem.deliveryState == null ? newItem.deliveryState == null : oldItem.deliveryState.equals(newItem.deliveryState));
            }
        });
    }

    @Override
    public int getItemViewType(int position) {
        MessageEntity message = getItem(position);
        if ("OUT".equals(message.direction)) {
            return VIEW_TYPE_OUT;
        } else {
            return VIEW_TYPE_IN;
        }
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view;
        if (viewType == VIEW_TYPE_OUT) {
            view = inflater.inflate(R.layout.item_message_out, parent, false);
        } else {
            view = inflater.inflate(R.layout.item_message_in, parent, false);
        }
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        MessageEntity message = getItem(position);
        holder.bind(message, timeFormat);
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;
        TextView timeText;
        ImageView statusIcon;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.messageText);
            timeText = itemView.findViewById(R.id.timeText);
            statusIcon = itemView.findViewById(R.id.statusIcon);
        }

        void bind(MessageEntity message, SimpleDateFormat timeFormat) {
            messageText.setText(message.content);
            if (timeText != null) {
                timeText.setText(timeFormat.format(new Date(message.sentAt)));
            }
            if (statusIcon != null) {
                if ("PENDING".equals(message.deliveryState)) {
                    statusIcon.setImageResource(android.R.drawable.ic_menu_upload);
                } else if ("SENT".equals(message.deliveryState)) {
                    statusIcon.setImageResource(android.R.drawable.ic_menu_send);
                } else if ("DELIVERED".equals(message.deliveryState)) {
                    // Ideally a double tick icon, but for now reuse send
                    statusIcon.setImageResource(android.R.drawable.ic_menu_view);
                } else if ("FAILED".equals(message.deliveryState)) {
                    statusIcon.setImageResource(android.R.drawable.ic_delete);
                }
            }
        }
    }
}
