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

        // Transport state is informational only; it never decides whether sending is safe.
        viewModel.getConnectionState().observe(this, state -> {
            binding.statusBar.setText(state);
            binding.statusBar.setBackgroundColor(
                    getResources().getColor("CONNECTED".equals(state)
                            ? R.color.colorAccent : R.color.warning_red, null));
        });

        // Sending is gated on the session, not the connection. There is deliberately no plaintext
        // fallback, so an unestablished session means the composer stays disabled.
        viewModel.getSessionState().observe(this, state -> {
            boolean established = "ESTABLISHED".equals(state);
            binding.sendButton.setEnabled(established);
            binding.messageInput.setEnabled(established);
            if (established) {
                binding.statusBar.setText(R.string.status_encrypted);
                binding.statusBar.setBackgroundColor(
                        getResources().getColor(R.color.colorAccent, null));
            }
        });

        // Key changes and authentication failures must be seen, not buried in a log.
        viewModel.getSecurityAlert().observe(this, alert -> {
            if (alert == null || alert.isEmpty()) {
                return;
            }
            binding.sendButton.setEnabled(false);
            binding.statusBar.setText(R.string.status_key_changed);
            binding.statusBar.setBackgroundColor(
                    getResources().getColor(R.color.warning_red, null));

            String safetyNumber = viewModel.getCurrentSafetyNumber();
            new androidx.appcompat.app.AlertDialog.Builder(this)
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
