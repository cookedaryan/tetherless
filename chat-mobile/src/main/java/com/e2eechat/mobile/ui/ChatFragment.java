package com.e2eechat.mobile.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.e2eechat.mobile.ChatService;
import com.e2eechat.mobile.ChatViewModel;
import com.e2eechat.mobile.MessageAdapter;
import com.e2eechat.mobile.R;
import com.e2eechat.mobile.databinding.FragmentChatBinding;

public class ChatFragment extends Fragment {

    private FragmentChatBinding binding;
    private ChatViewModel viewModel;
    private MessageAdapter adapter;
    private ChatService chatService;
    private boolean isBound = false;
    private String peerId;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ChatService.LocalBinder binder = (ChatService.LocalBinder) service;
            chatService = binder.getService();
            isBound = true;
            viewModel.setChatService(chatService);
            if (peerId != null) {
                viewModel.setConversationId(peerId);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            viewModel.setChatService(null);
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            peerId = getArguments().getString("peerId");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentChatBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        adapter = new MessageAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        layoutManager.setStackFromEnd(true);
        binding.chatRecyclerView.setLayoutManager(layoutManager);
        binding.chatRecyclerView.setAdapter(adapter);

        binding.sendButton.setOnClickListener(v -> {
            String text = binding.messageInput.getText().toString();
            viewModel.sendMessage(text);
            binding.messageInput.setText("");
        });

        viewModel.getMessages().observe(getViewLifecycleOwner(), messages -> {
            adapter.submitList(messages);
            if (messages != null && !messages.isEmpty()) {
                binding.chatRecyclerView.scrollToPosition(messages.size() - 1);
            }
        });

        viewModel.getConnectionState().observe(getViewLifecycleOwner(), state -> {
            binding.statusBar.setText(state);
            binding.statusBar.setBackgroundColor(
                    getResources().getColor("CONNECTED".equals(state)
                            ? R.color.colorAccent : R.color.warning_red, null));
        });

        viewModel.getSessionState().observe(getViewLifecycleOwner(), state -> {
            boolean established = "ESTABLISHED".equals(state);
            binding.sendButton.setEnabled(established);
            binding.messageInput.setEnabled(established);
            if (established) {
                binding.statusBar.setText(R.string.status_encrypted);
                binding.statusBar.setBackgroundColor(
                        getResources().getColor(R.color.colorAccent, null));
            }
        });

        viewModel.getSecurityAlert().observe(getViewLifecycleOwner(), alert -> {
            if (alert == null || alert.isEmpty()) {
                return;
            }
            binding.sendButton.setEnabled(false);
            binding.statusBar.setText(R.string.status_key_changed);
            binding.statusBar.setBackgroundColor(
                    getResources().getColor(R.color.warning_red, null));

            String safetyNumber = viewModel.getCurrentSafetyNumber();
            new AlertDialog.Builder(requireContext())
                    .setTitle("Security warning")
                    .setMessage(alert + "\n\nSafety number:\n"
                            + (safetyNumber == null ? "(no key received yet)" : safetyNumber))
                    .setPositiveButton("OK", (dialog, which) -> { })
                    .setCancelable(false)
                    .show();
        });

        binding.chatRecyclerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom < oldBottom) {
                binding.chatRecyclerView.post(() -> {
                    if (adapter.getItemCount() > 0) {
                        binding.chatRecyclerView.scrollToPosition(adapter.getItemCount() - 1);
                    }
                });
            }
        });

        // Bind Service
        Intent serviceIntent = new Intent(requireContext(), ChatService.class);
        requireContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (isBound) {
            requireContext().unbindService(serviceConnection);
            isBound = false;
        }
        binding = null;
    }
}
