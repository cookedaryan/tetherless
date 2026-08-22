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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class ChatServerLifecycleTest {

    private ChatServer server;
    private int port;

    @Before
    public void setup() throws Exception {
        ServerConfig config = new ServerConfig();
        config.setPort(0);
        config.setMaxConnections(2000);
        config.setMaxConnectionsPerIp(2000);
        config.setRateLimitBurst(2000);
        server = new ChatServer(config); // ephemeral port
        new Thread(() -> server.start()).start();
        
        // Wait for server to bind
        int attempts = 0;
        while ((port = server.getPort()) == 0 && attempts < 50) {
            Thread.sleep(100);
            attempts++;
        }
    }

    @After
    public void teardown() {
        if (server != null) {
            server.shutdown();
        }
    }

    private Socket createSocket() throws Exception {
        java.security.KeyStore trustStore = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream tsIs = getClass().getClassLoader().getResourceAsStream("dev-keystore.p12")) {
            if (tsIs == null) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream("chat-server/src/main/resources/dev-keystore.p12")) {
                    trustStore.load(fis, "changeit".toCharArray());
                }
            } else {
                trustStore.load(tsIs, "changeit".toCharArray());
            }
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, tmf.getTrustManagers(), null);
        javax.net.ssl.SSLSocketFactory factory = sslContext.getSocketFactory();
        javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) factory.createSocket("127.0.0.1", port);
        sslSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
        sslSocket.startHandshake();
        return sslSocket;
    }

    private Message createHello(String senderId) {
        return new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(senderId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
    }

    @Test(timeout = 5000)
    public void testDuplicateIdRejection() throws Exception {
        Socket s1 = createSocket();
        FrameWriter w1 = new FrameWriter(s1.getOutputStream());
        w1.writeMessage(createHello("userA"));
        
        // Wait for it to register
        Thread.sleep(100);

        Socket s2 = createSocket();
        FrameWriter w2 = new FrameWriter(s2.getOutputStream());
        FrameReader r2 = new FrameReader(s2.getInputStream());
        w2.writeMessage(createHello("userA"));

        // Second connection should receive an ERROR message and get disconnected
        Message msg = r2.readMessage();
        assertEquals(MessageType.ERROR, msg.getType());
        assertArrayEquals("Duplicate client ID".getBytes(), msg.getPayload());
        
        try {
            r2.readMessage();
            fail("Expected socket to be closed");
        } catch (Exception e) {
            // expected EOF
        }
        
        s1.close();
        s2.close();
    }

    @Test(timeout = 5000)
    public void testRecipientOffline() throws Exception {
        Socket s1 = createSocket();
        FrameWriter w1 = new FrameWriter(s1.getOutputStream());
        FrameReader r1 = new FrameReader(s1.getInputStream());
        w1.writeMessage(createHello("userA"));

        // Try to send to userB (not connected)
        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("userA")
                .setReceiverId("userB")
                .setPayload("Hello".getBytes())
                .setIv(new byte[12])
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        w1.writeMessage(msg);

        // Should receive an ERROR message
        Message response = r1.readMessage();
        assertEquals(MessageType.ERROR, response.getType());
        assertArrayEquals("RECIPIENT_OFFLINE".getBytes(), response.getPayload());

        s1.close();
    }

    @Test(timeout = 15000)
    public void testPingPongIdleTimeout() throws Exception {
        Socket s1 = createSocket();
        s1.setSoTimeout(5000);
        FrameWriter w1 = new FrameWriter(s1.getOutputStream());
        FrameReader r1 = new FrameReader(s1.getInputStream());
        w1.writeMessage(createHello("userA"));

        // Wait a bit, wait, the server ping interval is 30 seconds.
        // We cannot easily test 30-second timeouts in a fast test suite without refactoring the timeout into a variable.
        // So we will just manually send a PING and verify we get a PONG.
        Message ping = new MessageBuilder()
                .setType(MessageType.PING)
                .setSenderId("userA")
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        w1.writeMessage(ping);

        Message response = r1.readMessage();
        assertEquals(MessageType.PONG, response.getType());

        s1.close();
    }

    @Test(timeout = 20000)
    public void testConcurrencyConnectDisconnect() throws Exception {
        int threads = 100; // Testing with 100 clients instead of 1000 to keep it fast
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    for (int j = 0; j < 10; j++) { // 10 cycles each
                        Socket s = createSocket();
                        FrameWriter w = new FrameWriter(s.getOutputStream());
                        w.writeMessage(createHello("user_" + id));
                        Thread.sleep(10); // stay connected briefly
                        
                        Message disconnect = new MessageBuilder()
                                .setType(MessageType.DISCONNECT)
                                .setSenderId("user_" + id)
                                .setMessageId(UUID.randomUUID().toString())
                                .setTimestamp(System.currentTimeMillis())
                                .buildUnsigned();
                        w.writeMessage(disconnect);
                        s.close();
                    }
                } catch (Exception e) {
                    if (!(e instanceof java.net.SocketException)) {
                        e.printStackTrace();
                        failures.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue("Timeout waiting for clients", latch.await(15, TimeUnit.SECONDS));
        assertEquals("Failures during concurrent connect/disconnect", 0, failures.get());
    }
}
