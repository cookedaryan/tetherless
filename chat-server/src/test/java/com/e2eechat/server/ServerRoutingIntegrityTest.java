package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.protocol.MessageCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;

import static org.junit.Assert.*;

public class ServerRoutingIntegrityTest {

    private ChatServer server;
    private int port;

    @Before
    public void setup() throws Exception {
        ServerConfig config = new ServerConfig();
        config.setPort(0);
        config.setRateLimitBurst(1000); // Prevent rate limit from interfering with overflow test
        server = new ChatServer(config);
        new Thread(() -> server.start()).start();
        Thread.sleep(200);
        port = server.getPort();
    }

    @After
    public void teardown() {
        if (server != null) {
            server.shutdown();
        }
    }

    private Socket createSocket() throws IOException {
        return new Socket("localhost", port);
    }

    private void sendHello(FrameWriter w, String senderId) throws Exception {
        Message hello = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(senderId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        w.writeMessage(hello);
    }

    @Test(timeout = 10000)
    public void testRoutingVerbatimIntegrity() throws Exception {
        // Alice connects
        Socket aliceSocket = createSocket();
        FrameWriter aliceWriter = new FrameWriter(aliceSocket.getOutputStream());
        sendHello(aliceWriter, "alice");

        // Bob connects
        Socket bobSocket = createSocket();
        FrameWriter bobWriter = new FrameWriter(bobSocket.getOutputStream());
        FrameReader bobReader = new FrameReader(bobSocket.getInputStream());
        sendHello(bobWriter, "bob");

        Thread.sleep(100);

        // Alice sends a message to Bob
        byte[] payload = "Top secret message".getBytes();
        byte[] iv = new byte[12];
        byte[] fakeSignature = "FakeSignature123".getBytes();

        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .setPayload(payload)
                .setIv(iv)
                .setSignature(fakeSignature)
                .build();

        aliceWriter.writeMessage(msg);

        // Bob reads the message
        Message receivedMsg = bobReader.readMessage();

        // Verify the received message has exactly the same signature and payload.
        // If the server re-encoded it without carrying over the signature, or altered anything, this fails.
        assertArrayEquals(fakeSignature, receivedMsg.getSignature());
        assertArrayEquals(payload, receivedMsg.getPayload());

        aliceSocket.close();
        bobSocket.close();
    }

    @Test(timeout = 10000)
    public void testSlowRecipientOverflowPolicy() throws Exception {
        // SlowBob connects
        Socket bobSocket = createSocket();
        bobSocket.setReceiveBufferSize(256);
        FrameWriter bobWriter = new FrameWriter(bobSocket.getOutputStream());
        FrameReader bobReader = new FrameReader(bobSocket.getInputStream());
        sendHello(bobWriter, "slow_bob");

        // Alice connects
        Socket aliceSocket = createSocket();
        FrameWriter aliceWriter = new FrameWriter(aliceSocket.getOutputStream());
        FrameReader aliceReader = new FrameReader(aliceSocket.getInputStream());
        sendHello(aliceWriter, "alice");

        Thread.sleep(100);

        // Alice floods SlowBob who never reads
        byte[] payload = new byte[1024]; // 1KB per message to fill OS buffer fast
        int sent = 0;
        
        try {
            // Queue capacity is 256. 500 will definitely overflow it.
            for (int i = 0; i < 500; i++) {
                Message msg = new MessageBuilder()
                        .setType(MessageType.TEXT_MESSAGE)
                        .setSenderId("alice")
                        .setReceiverId("slow_bob")
                        .setMessageId(UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .setPayload(payload)
                        .setIv(new byte[12])
                        .buildUnsigned();
                aliceWriter.writeMessage(msg);
                sent++;
            }
        } catch (Exception e) {
            // Alice got disconnected because she didn't read RECIPIENT_OFFLINEs and her queue overflowed
            // This is acceptable, we just want to prove Bob gets disconnected
        }

        // Bob should get disconnected
        boolean disconnected = false;
        try {
            while (true) {
                Message msg = bobReader.readMessage();
                if (msg.getType() == MessageType.ERROR && new String(msg.getPayload()).equals("BUFFER_OVERFLOW")) {
                    disconnected = true;
                    break;
                }
            }
        } catch (Exception e) {
            // Socket was closed by the server
            disconnected = true;
        }

        assertTrue("Bob should have been disconnected", disconnected);

        aliceSocket.close();
        bobSocket.close();
    }
}
