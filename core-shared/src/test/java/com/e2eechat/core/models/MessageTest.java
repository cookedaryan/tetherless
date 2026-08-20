package com.e2eechat.core.models;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MessageTest {

    @Test
    public void testTextMessageRequiresIv() {
        try {
            new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setPayload(new byte[]{1, 2, 3})
                .setMessageId("msg-1")
                .setTimestamp(1000L)
                .buildUnsigned();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("IV is required"));
        }
    }

    @Test
    public void testCanonicalBytesIdentical() {
        Message msg1 = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setPayload(new byte[]{1, 2, 3})
                .setIv(new byte[]{4, 5, 6})
                .setMessageId("msg-1")
                .setTimestamp(1000L)
                .setProtocolVersion(1)
                .buildUnsigned();

        Message msg2 = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setPayload(new byte[]{1, 2, 3})
                .setIv(new byte[]{4, 5, 6})
                .setMessageId("msg-1")
                .setTimestamp(1000L)
                .setProtocolVersion(1)
                .buildUnsigned();

        assertArrayEquals(msg1.canonicalBytesForSigning(), msg2.canonicalBytesForSigning());
    }
}
