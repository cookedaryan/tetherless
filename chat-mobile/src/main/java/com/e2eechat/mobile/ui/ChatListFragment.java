package com.e2eechat.mobile.ui;

import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.e2eechat.mobile.R;
import com.e2eechat.mobile.databinding.FragmentChatListBinding;

public class ChatListFragment extends Fragment {

    private FragmentChatListBinding binding;
    private ChatListViewModel viewModel;
    private ChatListAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentChatListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(ChatListViewModel.class);

        adapter = new ChatListAdapter(peerId -> openChat(peerId));
        binding.chatListRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.chatListRecyclerView.setAdapter(adapter);

        viewModel.getRecentConversations().observe(getViewLifecycleOwner(), messages -> {
            adapter.submitList(messages);
        });

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_chat_list, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_settings) {
                    Navigation.findNavController(requireView()).navigate(R.id.action_chatListFragment_to_settingsFragment);
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        binding.fabNewChat.setOnClickListener(v -> showNewChatDialog());
    }

    private void showNewChatDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("New Chat");

        final EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Enter Peer ID");
        builder.setView(input);

        builder.setPositiveButton("Start", (dialog, which) -> {
            String peerId = input.getText().toString().trim();
            if (!peerId.isEmpty()) {
                openChat(peerId);
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void openChat(String peerId) {
        Bundle args = new Bundle();
        args.putString("peerId", peerId);
        args.putString("peerName", peerId); // In a real app we'd look up the name
        Navigation.findNavController(requireView()).navigate(R.id.action_chatListFragment_to_chatFragment, args);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
