package com.e2eechat.mobile;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import java.util.List;

public class ChatViewModel extends AndroidViewModel {

    private final MessageDao messageDao;
    private final MutableLiveData<String> currentConversationId = new MutableLiveData<>();
    
    // LiveData that updates whenever the conversationId changes
    private final LiveData<List<MessageEntity>> messages;
    
    // UI state for connection
    private final MutableLiveData<String> connectionState = new MutableLiveData<>("DISCONNECTED");

    public ChatViewModel(@NonNull Application application) {
        super(application);
        messageDao = DatabaseProvider.getDatabase(application).messageDao();
        
        messages = Transformations.switchMap(currentConversationId, convoId -> 
            // In a real app we'd use Paging, but for now we'll just get a large limit
            messageDao.getConversation(convoId, 1000, 0)
        );
    }

    public void setConversationId(String peerId) {
        currentConversationId.setValue(peerId);
        // Here we would also bind to ChatService to connect
        connectionState.setValue("CONNECTING");
    }

    public LiveData<List<MessageEntity>> getMessages() {
        return messages;
    }

    public LiveData<String> getConnectionState() {
        return connectionState;
    }

    private ChatService chatService;

    public void setChatService(ChatService chatService) {
        this.chatService = chatService;
        // Bind service states
        chatService.getConnectionStateLiveData().observeForever(state -> {
            connectionState.postValue(state);
        });
    }

    public void sendMessage(String text) {
        String peerId = currentConversationId.getValue();
        if (peerId == null || text.trim().isEmpty()) return;
        
        // Save to DB immediately as pending
        MessageEntity msg = new MessageEntity();
        msg.messageId = java.util.UUID.randomUUID().toString();
        msg.conversationId = peerId;
        msg.content = text;
        msg.direction = "OUT";
        msg.deliveryState = "PENDING";
        msg.sentAt = System.currentTimeMillis();
        msg.receivedAt = msg.sentAt;
        
        new Thread(() -> {
            messageDao.insert(msg);
            if (chatService != null) {
                chatService.sendTextMessage(peerId, text, msg.messageId);
            }
        }).start();
    }
    
    public String getCurrentSafetyNumber() {
        return "12345 67890 12345 67890";
    }
    
    public void trustCurrentPeer() {
        if (chatService != null) {
            chatService.trustPeer(currentConversationId.getValue());
        }
        connectionState.setValue("ESTABLISHED");
    }
}
