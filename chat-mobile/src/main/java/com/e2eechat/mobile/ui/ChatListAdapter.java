package com.e2eechat.mobile.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.e2eechat.mobile.MessageEntity;
import com.e2eechat.mobile.PeerNames;
import com.e2eechat.mobile.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ChatListAdapter extends ListAdapter<MessageEntity, ChatListAdapter.ChatViewHolder> {

    private final OnChatClickListener listener;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public interface OnChatClickListener {
        void onChatClick(String peerId);
    }

    public ChatListAdapter(OnChatClickListener listener) {
        super(new DiffUtil.ItemCallback<MessageEntity>() {
            @Override
            public boolean areItemsTheSame(@NonNull MessageEntity oldItem, @NonNull MessageEntity newItem) {
                return oldItem.conversationId.equals(newItem.conversationId);
            }

            @Override
            public boolean areContentsTheSame(@NonNull MessageEntity oldItem, @NonNull MessageEntity newItem) {
                return oldItem.id == newItem.id;
            }
        });
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_list, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        MessageEntity message = getItem(position);
        holder.bind(message);
    }

    class ChatViewHolder extends RecyclerView.ViewHolder {
        private final TextView peerNameText;
        private final TextView messagePreviewText;
        private final TextView timeText;

        public ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            peerNameText = itemView.findViewById(R.id.peerNameText);
            messagePreviewText = itemView.findViewById(R.id.messagePreviewText);
            timeText = itemView.findViewById(R.id.timeText);

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    listener.onChatClick(getItem(pos).conversationId);
                }
            });
        }

        public void bind(MessageEntity message) {
            String name = PeerNames.get(itemView.getContext(), message.conversationId);
            if (name == null || name.isEmpty()) {
                name = message.conversationId; // Fallback
            }
            peerNameText.setText(name);
            messagePreviewText.setText(message.content);
            timeText.setText(dateFormat.format(new Date(message.sentAt)));
        }
    }
}
