package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class ChatServerTest {

    private ChatServer server;
    private int port;

    @Before
    public void setup() throws Exception {
        ServerConfig config = new ServerConfig();
        config.setPort(0);
        config.setRateLimitBurst(2000); // disable rate limiting for tests
        config.setMaxConnections(200);
        config.setMaxConnectionsPerIp(200);
        config.setHandshakeTimeoutMs(1000);
        server = new ChatServer(config);
        new Thread(() -> server.start()).start();
        
        // Wait for server to bind and get a non-zero port
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

    @Test(timeout = 5000, expected = Exception.class)
    public void testPlaintextConnectionRejected() throws Exception {
        TestClient alice = new TestClient("alice");
        // Connecting with plaintext Socket to an SSLServerSocket
        alice.connectPlaintext(port);
        alice.sendHello(); // Should throw exception when server drops connection due to TLS handshake failure
        alice.awaitMessage(1000);
        alice.close();
    }

    @Test(timeout = 5000)
    public void testTwoClientRouting() throws Exception {
        TestClient alice = new TestClient("alice");
        TestClient bob = new TestClient("bob");

        alice.connect(port);
        bob.connect(port);

        alice.sendHello();
        bob.sendHello();

        Thread.sleep(100);

        alice.sendText("bob", "Hello Bob!");
        
        Message msg = bob.awaitMessage(1000);
        assertNotNull("Bob should receive a message", msg);
        assertEquals("alice", msg.getSenderId());
        assertEquals("Hello Bob!", new String(msg.getPayload()));

        alice.close();
        bob.close();
    }

    @Test(timeout = 5000)
    public void testOfflineRecipient() throws Exception {
        TestClient alice = new TestClient("alice");
        alice.connect(port);
        alice.sendHello();
        Thread.sleep(100);

        alice.sendText("charlie", "Are you there?");
        
        Message msg = alice.awaitMessage(1000);
        assertNotNull("Alice should receive an error", msg);
        assertEquals(MessageType.ERROR, msg.getType());
        assertEquals("RECIPIENT_OFFLINE", new String(msg.getPayload()));
        
        alice.close();
    }

    @Test(timeout = 5000)
    public void testDuplicateId() throws Exception {
        TestClient alice1 = new TestClient("alice");
        alice1.connect(port);
        alice1.sendHello();
        Thread.sleep(100);

        TestClient alice2 = new TestClient("alice");
        alice2.connect(port);
        alice2.sendHello();
        
        Message msg = alice2.awaitMessage(1000);
        assertNotNull(msg);
        assertEquals(MessageType.ERROR, msg.getType());
        assertEquals("Duplicate client ID", new String(msg.getPayload()));
        
        alice2.assertDisconnected();
        alice1.close();
        alice2.close();
    }

    @Test(timeout = 5000)
    public void testOversizedFrameRejection() throws Exception {
        TestClient alice = new TestClient("alice");
        alice.connect(port);
        
        // Frame length > 1MB
        int oversizedLength = 2 * 1024 * 1024; 
        ByteBuffer buffer = ByteBuffer.allocate(4 + oversizedLength);
        buffer.putInt(oversizedLength);
        byte[] payload = new byte[oversizedLength];
        buffer.put(payload);
        
        try {
            alice.sendRawBytes(buffer.array());
            alice.assertDisconnected();
        } catch (java.net.SocketException e) {
            // Success: Server dropped connection before we could finish writing the 2MB frame
        }
        alice.close();
    }

    @Test(timeout = 5000)
    public void testMalformedFrameRejection() throws Exception {
        TestClient alice = new TestClient("alice");
        alice.connect(port);
        
        // Valid frame length, but garbage protobuf
        ByteBuffer buffer = ByteBuffer.allocate(14);
        buffer.putInt(10);
        buffer.put(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        
        alice.sendRawBytes(buffer.array());
        
        alice.assertDisconnected();
        alice.close();
    }

    @Test(timeout = 15000)
    public void testIdleTimeout() throws Exception {
        TestClient alice = new TestClient("alice");
        alice.connect(port);
        
        // Just wait. Handshake timeout is 10s by default. 
        // We'll give it a bit of time, and wait for socket drop.
        alice.assertDisconnected();
        alice.close();
    }
    
    @Test(timeout = 5000)
    public void testAbruptDisconnect() throws Exception {
        TestClient alice = new TestClient("alice");
        alice.connect(port);
        alice.sendHello();
        Thread.sleep(100);
        
        // Abruptly close socket client side
        alice.close();
        
        Thread.sleep(200);
        
        // Bob shouldn't be able to route to Alice
        TestClient bob = new TestClient("bob");
        bob.connect(port);
        bob.sendHello();
        Thread.sleep(100);
        
        bob.sendText("alice", "U there?");
        
        Message error = bob.awaitMessage(1000);
        assertNotNull(error);
        assertEquals(MessageType.ERROR, error.getType());
        assertEquals("RECIPIENT_OFFLINE", new String(error.getPayload()));
        
        bob.close();
    }

    @Test(timeout = 60000)
    public void testHighConcurrencyRouting() throws Exception {
        int senders = 10;
        int messagesPerSender = 1000;
        int expectedTotal = senders * messagesPerSender;

        TestClient recipient = new TestClient("recipient");
        recipient.connect(port);
        recipient.setReceiveBufferSize(10 * 1024 * 1024); // 10MB to prevent OS backpressure and queue overflow
        recipient.sendHello();

        List<TestClient> clients = new ArrayList<>();
        for (int i = 0; i < senders; i++) {
            TestClient c = new TestClient("sender" + i);
            c.connect(port);
            c.sendHello();
            clients.add(c);
        }
        
        Thread.sleep(500); // Allow all to register

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(senders);

        for (int i = 0; i < senders; i++) {
            TestClient c = clients.get(i);
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < messagesPerSender; j++) {
                        c.sendText("recipient", "msg" + j);
                        Thread.sleep(1);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch receiveLatch = new CountDownLatch(expectedTotal);
        
        new Thread(() -> {
            try {
                recipient.setSoTimeout(10000);
                while (receivedCount.get() < expectedTotal) {
                    Message m = recipient.awaitMessage(10000);
                    if (m != null && m.getType() == MessageType.TEXT_MESSAGE) {
                        receivedCount.incrementAndGet();
                        receiveLatch.countDown();
                    } else if (m == null) {
                        break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        // Release the hounds
        startLatch.countDown();

        assertTrue("Senders did not finish in time", doneLatch.await(25, TimeUnit.SECONDS));
        boolean success = receiveLatch.await(25, TimeUnit.SECONDS);
        System.out.println("Received: " + receivedCount.get() + " / " + expectedTotal);
        System.out.println("Buffer Overflow Drops: " + Metrics.rejectedBufferOverflow.get());
        assertTrue("Recipient failed to receive all messages", success);
        
        assertEquals(expectedTotal, receivedCount.get());

        for (TestClient c : clients) {
            c.close();
        }
        recipient.close();
    }
}
