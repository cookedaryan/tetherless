package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class ServerResourceLimitsTest {

    private ChatServer server;
    private ServerConfig config;
    private int port;

    @Before
    public void setup() throws Exception {
        config = new ServerConfig();
        config.setPort(0);
        config.setMaxConnections(5);
        config.setMaxConnectionsPerIp(3);
        config.setRateLimitBurst(2);
        config.setRateLimitRefillSec(1);
        config.setHandshakeTimeoutMs(1000); // Fast timeout for testing

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

    @Test(timeout = 5000)
    public void testMaxConnectionsPerIp() throws Exception {
        List<Socket> sockets = new ArrayList<>();
        
        // Connect 3 sockets successfully
        for (int i = 0; i < 3; i++) {
            Socket s = createSocket();
            sockets.add(s);
            FrameWriter w = new FrameWriter(s.getOutputStream());
            sendHello(w, "user" + i);
        }

        Thread.sleep(100);

        // The 4th connection from localhost should be rejected
        Socket rejected = createSocket();
        FrameReader r = new FrameReader(rejected.getInputStream());
        
        Message msg = r.readMessage();
        assertEquals(MessageType.ERROR, msg.getType());
        assertEquals("TOO_MANY_CONNECTIONS", new String(msg.getPayload()));
        
        // Attempting to read again should yield EOF because socket is closed
        try {
            r.readMessage();
            fail("Expected EOFException");
        } catch (Exception e) {
            // Success
        }

        for (Socket s : sockets) {
            s.close();
        }
    }

    @Test(timeout = 5000)
    public void testRateLimitBurst() throws Exception {
        Socket s = createSocket();
        FrameWriter w = new FrameWriter(s.getOutputStream());
        FrameReader r = new FrameReader(s.getInputStream());
        sendHello(w, "spammer");

        Thread.sleep(100);

        // Burst is 10. Sending 15 messages quickly should trigger a disconnect.
        int sent = 0;
        try {
            for (int i = 0; i < 15; i++) {
                Message text = new MessageBuilder()
                        .setType(MessageType.TEXT_MESSAGE)
                        .setSenderId("spammer")
                        .setReceiverId("victim")
                        .setMessageId(UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .setPayload("spam".getBytes())
                        .setIv(new byte[12])
                        .setSignature(new byte[32])
                        .build();
                w.writeMessage(text);
                sent++;
            }
        } catch (Exception e) {
            // Might be closed while writing
        }

        // We should receive RATE_LIMIT_EXCEEDED or a disconnected socket
        boolean rateLimited = false;
        try {
            while (true) {
                Message msg = r.readMessage();
                if (msg.getType() == MessageType.ERROR && "RATE_LIMIT_EXCEEDED".equals(new String(msg.getPayload()))) {
                    rateLimited = true;
                    break;
                }
            }
        } catch (Exception e) {
            // Connection reset because server dropped us for rate limiting!
            rateLimited = true;
        }

        assertTrue("Client should have been rate limited after bursting", rateLimited);
        s.close();
    }

    @Test(timeout = 5000)
    public void testHandshakeTimeout() throws Exception {
        Socket s = createSocket();
        FrameReader r = new FrameReader(s.getInputStream());
        
        // Connect but send nothing. Handshake timeout is 1000ms.
        long start = System.currentTimeMillis();
        
        try {
            r.readMessage(); // Should throw EOFException when server closes it
            fail("Expected socket to be closed by server");
        } catch (Exception e) {
            // Success
        }
        
        long duration = System.currentTimeMillis() - start;
        assertTrue("Duration should be at least handshake timeout", duration >= 900);
        
        s.close();
    }
}
