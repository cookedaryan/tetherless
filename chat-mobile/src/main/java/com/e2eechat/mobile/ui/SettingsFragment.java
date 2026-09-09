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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.e2eechat.mobile.ChatService;
import com.e2eechat.mobile.MobileConfig;
import com.e2eechat.mobile.databinding.FragmentSettingsBinding;

public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private ChatService chatService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ChatService.LocalBinder binder = (ChatService.LocalBinder) service;
            chatService = binder.getService();
            isBound = true;
            if (binding != null) {
                binding.peerIdText.setText(chatService.getClientId());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.nameInput.setText(MobileConfig.displayName(requireContext()));
        binding.hostInput.setText(MobileConfig.relayHost(requireContext()));
        binding.portInput.setText(String.valueOf(MobileConfig.relayPort(requireContext())));

        binding.saveButton.setOnClickListener(v -> {
            String name = binding.nameInput.getText().toString().trim();
            String host = binding.hostInput.getText().toString().trim();
            String portStr = binding.portInput.getText().toString().trim();

            if (name.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                Toast.makeText(requireContext(), "All fields required", Toast.LENGTH_SHORT).show();
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                Toast.makeText(requireContext(), "Invalid port", Toast.LENGTH_SHORT).show();
                return;
            }

            MobileConfig.setDisplayName(requireContext(), name);
            MobileConfig.setRelay(requireContext(), host, port);

            if (isBound && chatService != null) {
                chatService.restartConnection();
                Toast.makeText(requireContext(), "Settings saved. Reconnecting...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show();
            }
        });

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
