package com.e2eechat.mobile.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.e2eechat.mobile.DatabaseProvider;
import com.e2eechat.mobile.MessageDao;
import com.e2eechat.mobile.MessageEntity;

import java.util.List;

public class ChatListViewModel extends AndroidViewModel {

    private final MessageDao messageDao;
    private final LiveData<List<MessageEntity>> recentConversations;

    public ChatListViewModel(@NonNull Application application) {
        super(application);
        messageDao = DatabaseProvider.getDatabase(application).messageDao();
        recentConversations = messageDao.getRecentConversations();
    }

    public LiveData<List<MessageEntity>> getRecentConversations() {
        return recentConversations;
    }
}
