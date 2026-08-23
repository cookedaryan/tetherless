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
    private final MutableLiveData<String> sessionState = new MutableLiveData<>("IDLE");
    private final MutableLiveData<String> securityAlert = new MutableLiveData<>();

    public ChatViewModel(@NonNull Application application) {
        super(application);
        messageDao = DatabaseProvider.getDatabase(application).messageDao();
        
        messages = Transformations.switchMap(currentConversationId, convoId -> 
            // In a real app we'd use Paging, but for now we'll just get a large limit
            messageDao.getConversation(convoId, 1000, 0)
        );
    }

    /**
     * Opens a conversation and begins its key exchange. Selecting a peer used only to change the
     * displayed conversation, so the session stayed IDLE and the composer never unlocked.
     */
    public void setConversationId(String peerId) {
        currentConversationId.setValue(peerId);
        sessionState.setValue("HANDSHAKE_SENT");
        if (chatService != null) {
            chatService.startSecureChat(peerId);
        }
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
        chatService.getConnectionStateLiveData().observeForever(connectionState::postValue);
        chatService.getSessionStateLiveData().observeForever(sessionState::postValue);
        chatService.getSecurityAlertLiveData().observeForever(securityAlert::postValue);
    }

    /** Whether the key exchange with the open conversation has completed. */
    public LiveData<String> getSessionState() {
        return sessionState;
    }

    /** Key changes and authentication failures the user must be shown. */
    public LiveData<String> getSecurityAlert() {
        return securityAlert;
    }

    /**
     * Sends a message. Persistence, encryption and signing all happen in {@link ChatService} on its
     * crypto thread; this only forwards. The view model used to write the row itself, which meant
     * the message existed in two places and could be stored without ever being encrypted.
     */
    public void sendMessage(String text) {
        String peerId = currentConversationId.getValue();
        if (peerId == null || text.trim().isEmpty() || chatService == null) {
            return;
        }
        chatService.sendTextMessage(peerId, text);
    }

    /**
     * The safety number to compare with this peer out of band, or null if their key has not been
     * received yet. Previously this returned a fixed string, which looked like verification while
     * proving nothing.
     */
    public String getCurrentSafetyNumber() {
        String peerId = currentConversationId.getValue();
        if (peerId == null || chatService == null) {
            return null;
        }
        return chatService.safetyNumberFor(peerId);
    }

    /** This device's own peer id, for sharing so others can start a chat. */
    public String getOwnPeerId() {
        return chatService == null ? null : chatService.getClientId();
    }

    /** Begins the key exchange with the open conversation. */
    public void startSecureChat() {
        String peerId = currentConversationId.getValue();
        if (peerId != null && chatService != null) {
            chatService.startSecureChat(peerId);
        }
    }
}
