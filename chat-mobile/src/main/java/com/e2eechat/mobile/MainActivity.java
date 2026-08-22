package com.e2eechat.mobile;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.e2eechat.mobile.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ChatViewModel viewModel;
    private MessageAdapter adapter;
    private ChatService chatService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ChatService.LocalBinder binder = (ChatService.LocalBinder) service;
            chatService = binder.getService();
            isBound = true;
            viewModel.setChatService(chatService);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            viewModel.setChatService(null);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        // Start and bind Foreground Service
        Intent serviceIntent = new Intent(this, ChatService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        adapter = new MessageAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        binding.chatRecyclerView.setLayoutManager(layoutManager);
        binding.chatRecyclerView.setAdapter(adapter);

        binding.startChatButton.setOnClickListener(v -> {
            String peerId = binding.peerIdInput.getText().toString().trim();
            if (!peerId.isEmpty()) {
                viewModel.setConversationId(peerId);
                binding.peerSelectionLayout.setVisibility(View.GONE);
            }
        });

        binding.sendButton.setOnClickListener(v -> {
            String text = binding.messageInput.getText().toString();
            viewModel.sendMessage(text);
            binding.messageInput.setText("");
        });

        viewModel.getMessages().observe(this, messages -> {
            adapter.submitList(messages);
            if (messages != null && !messages.isEmpty()) {
                binding.chatRecyclerView.scrollToPosition(messages.size() - 1);
            }
        });

        viewModel.getConnectionState().observe(this, state -> {
            binding.statusBar.setText(state);
            if ("CONNECTED".equals(state) || "ESTABLISHED".equals(state)) {
                binding.sendButton.setEnabled(true);
                binding.statusBar.setBackgroundColor(getResources().getColor(R.color.colorAccent, null));
            } else if ("KEY_CHANGED".equals(state)) {
                binding.statusBar.setText(R.string.status_key_changed);
                binding.statusBar.setBackgroundColor(getResources().getColor(R.color.warning_red, null));
                binding.sendButton.setEnabled(false);
                
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Trust Verification")
                        .setMessage("The identity key for this peer has changed or is new.\n\nSafety Number: " + viewModel.getCurrentSafetyNumber())
                        .setPositiveButton("Verify & Trust", (dialog, which) -> {
                            viewModel.trustCurrentPeer();
                        })
                        .setNegativeButton("Block", (dialog, which) -> {})
                        .setCancelable(false)
                        .show();
            } else {
                binding.sendButton.setEnabled(true);
            }
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
