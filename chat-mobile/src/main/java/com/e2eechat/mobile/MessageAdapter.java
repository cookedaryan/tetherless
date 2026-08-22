package com.e2eechat.mobile;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

public class MessageAdapter extends ListAdapter<MessageEntity, MessageAdapter.MessageViewHolder> {

    private static final int VIEW_TYPE_IN = 1;
    private static final int VIEW_TYPE_OUT = 2;

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
        holder.bind(message);
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.messageText);
        }

        void bind(MessageEntity message) {
            messageText.setText(message.content);
        }
    }
}
