package com.e2eechat.mobile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;


import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)

public class MessageDaoTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private AppDatabase database;
    private MessageDao messageDao;

    @Before
    public void setup() {
        database = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        messageDao = database.messageDao();
    }

    @After
    public void teardown() {
        database.close();
    }

    // Helper to get LiveData value synchronously
    private <T> T getOrAwaitValue(final LiveData<T> liveData) throws InterruptedException {
        final Object[] data = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        Observer<T> observer = new Observer<T>() {
            @Override
            public void onChanged(T o) {
                data[0] = o;
                latch.countDown();
                liveData.removeObserver(this);
            }
        };
        liveData.observeForever(observer);
        latch.await(2, TimeUnit.SECONDS);
        return (T) data[0];
    }

    @Test
    public void testInsertAndGetConversation() throws Exception {
        MessageEntity msg1 = new MessageEntity();
        msg1.messageId = "msg-1";
        msg1.conversationId = "Bob";
        msg1.content = "Hello Bob";
        msg1.sentAt = 1000;
        msg1.receivedAt = 1001;

        MessageEntity msg2 = new MessageEntity();
        msg2.messageId = "msg-2";
        msg2.conversationId = "Bob";
        msg2.content = "How are you?";
        msg2.sentAt = 2000;
        msg2.receivedAt = 2001;
        
        MessageEntity msg3 = new MessageEntity();
        msg3.messageId = "msg-3";
        msg3.conversationId = "Alice"; // different convo
        msg3.content = "Hi Alice";
        msg3.sentAt = 3000;
        msg3.receivedAt = 3001;

        messageDao.insert(msg1);
        messageDao.insert(msg2);
        messageDao.insert(msg3);

        List<MessageEntity> bobConvo = getOrAwaitValue(messageDao.getConversation("Bob", 10, 0));
        assertEquals(2, bobConvo.size());
        assertEquals("msg-1", bobConvo.get(0).messageId);
        assertEquals("msg-2", bobConvo.get(1).messageId);
        
        // Test update delivery state
        messageDao.updateDeliveryState("msg-1", "DELIVERED");
        
        List<MessageEntity> bobConvoUpdated = getOrAwaitValue(messageDao.getConversation("Bob", 10, 0));
        assertEquals("DELIVERED", bobConvoUpdated.get(0).deliveryState);
        
        // Test insert duplicate ignores
        MessageEntity duplicate = new MessageEntity();
        duplicate.messageId = "msg-1";
        duplicate.conversationId = "Bob";
        messageDao.insert(duplicate);
        
        List<MessageEntity> afterDuplicate = getOrAwaitValue(messageDao.getConversation("Bob", 10, 0));
        assertEquals(2, afterDuplicate.size()); // still 2
        assertEquals("Hello Bob", afterDuplicate.get(0).content); // content not overwritten
    }
}
