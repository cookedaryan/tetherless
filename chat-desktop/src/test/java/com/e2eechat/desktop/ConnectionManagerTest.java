package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.ConnectionManager;
import com.e2eechat.core.network.ConnectionState;
import com.e2eechat.core.network.MessageListener;
import com.e2eechat.core.network.TlsSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives {@link ConnectionManager} against {@link StubRelay}, a real TLS endpoint.
 *
 * <p>Nothing here stubs out {@code connectInternal()}: the manager runs its production connect
 * path, so the handshake, the HELLO it opens with, framing, the reconnect loop and the shutdown
 * handshake are all genuinely covered.
 */
public class ConnectionManagerTest {

    /** Loopback TLS and framing are fast; anything slower than this is a failure, not a wait. */
    private static final long TIMEOUT_MILLIS = 5000;

    private StubRelay relay;
    private ConnectionManager manager;
    private final List<ConnectionState> states = new CopyOnWriteArrayList<>();
    private final List<Message> delivered = new CopyOnWriteArrayList<>();

    private final MessageListener listener = new MessageListener() {
        @Override
        public void onMessageReceived(Message message) {
            delivered.add(message);
        }

        @Override
        public void onConnectionStateChanged(ConnectionState state) {
            states.add(state);
        }
    };

    @Before
    public void setUp() throws Exception {
        // Another test in this module varies the configured truststore; make sure this one always
        // pins the development certificate the stub relay actually presents.
        System.clearProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        TlsSupport.resetCachedContext();
        relay = new StubRelay();
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.stop();
        }
        if (relay != null) {
            relay.close();
        }
    }

    /** A manager whose retry delay is zero, so a test never waits out a real backoff. */
    private ConnectionManager immediate() {
        return new ConnectionManager("127.0.0.1", relay.port(), "alice", listener) {
            @Override
            protected long backoffDelayMillis(int attempt) {
                return 0;
            }
        };
    }

    @Test
    public void announcesItselfWithHelloOnConnect() throws Exception {
        manager = immediate();
        manager.start();

        Message hello = relay.awaitFrame(TIMEOUT_MILLIS);
        assertNotNull("no frame reached the relay", hello);
        assertEquals(MessageType.HELLO, hello.getType());
        assertEquals("alice", hello.getSenderId());
    }

    @Test
    public void reportsConnectingThenConnected() throws Exception {
        manager = immediate();
        manager.start();

        assertNotNull(relay.awaitFrame(TIMEOUT_MILLIS));

        assertTrue("never reported CONNECTING: " + states,
                states.contains(ConnectionState.CONNECTING));
        assertTrue("never reported CONNECTED: " + states,
                states.contains(ConnectionState.CONNECTED));
        assertEquals(ConnectionState.CONNECTING, states.get(0));
    }

    @Test
    public void reconnectsAfterTheRelayDropsTheConnection() throws Exception {
        manager = immediate();
        manager.start();

        assertNotNull("first HELLO never arrived", relay.awaitFrame(TIMEOUT_MILLIS));
        assertNotNull(relay.awaitConnection(TIMEOUT_MILLIS));

        relay.dropCurrentConnection();

        Message secondHello = relay.awaitFrame(TIMEOUT_MILLIS);
        assertNotNull("client did not reconnect after the relay dropped it", secondHello);
        assertEquals(MessageType.HELLO, secondHello.getType());
        assertTrue("expected a second connection, saw " + relay.connectionCount(),
                relay.connectionCount() >= 2);
        assertTrue("never reported RECONNECTING: " + states,
                states.contains(ConnectionState.RECONNECTING));
    }

    /**
     * A relay that hangs up the instant the handshake completes must not be met with an
     * unthrottled retry loop.
     *
     * <p>The backoff counter used to be reset on every successful connect, so this shape reset it
     * on every cycle and the client reconnected with no delay at all, for as long as the relay
     * kept behaving that way. The counter now advances until a connection actually stands up.
     */
    @Test
    public void backsOffWhenConnectionsKeepDroppingImmediately() throws Exception {
        relay.hangUpOnConnect(true);

        List<Integer> attempts = new CopyOnWriteArrayList<>();
        manager = new ConnectionManager("127.0.0.1", relay.port(), "alice", listener) {
            @Override
            protected long backoffDelayMillis(int attempt) {
                attempts.add(attempt);
                return 0;
            }
        };
        manager.start();

        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (attempts.size() < 4 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        manager.stop();

        assertTrue("the reconnect loop never ran: " + attempts, attempts.size() >= 4);
        int highest = 0;
        for (Integer attempt : attempts) {
            highest = Math.max(highest, attempt);
        }
        assertTrue("backoff never advanced past attempt 0, so the loop is unthrottled: " + attempts,
                highest >= 3);
    }

    @Test
    public void answersPingWithPong() throws Exception {
        manager = immediate();
        manager.start();

        StubRelay.Connection connection = relay.awaitConnection(TIMEOUT_MILLIS);
        assertNotNull(connection);
        assertNotNull(relay.awaitFrame(TIMEOUT_MILLIS));

        connection.send(new MessageBuilder()
                .setType(MessageType.PING)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned());

        Message pong = relay.awaitFrame(TIMEOUT_MILLIS);
        assertNotNull("no reply to PING", pong);
        assertEquals(MessageType.PONG, pong.getType());
    }

    @Test
    public void handsRelayedFramesToTheListener() throws Exception {
        manager = immediate();
        manager.start();

        StubRelay.Connection connection = relay.awaitConnection(TIMEOUT_MILLIS);
        assertNotNull(connection);
        assertNotNull(relay.awaitFrame(TIMEOUT_MILLIS));

        connection.send(new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("bob")
                .setReceiverId("alice")
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .setPayload("ciphertext".getBytes(StandardCharsets.UTF_8))
                .setIv(new byte[12])
                .setSignature(new byte[32])
                .build());

        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (delivered.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, delivered.size());
        assertEquals(MessageType.TEXT_MESSAGE, delivered.get(0).getType());
        assertEquals("bob", delivered.get(0).getSenderId());
    }

    /**
     * PING and DISCONNECT are handled by the transport and are not application traffic; forwarding
     * them would put a bare protocol frame in front of the user.
     */
    @Test
    public void doesNotForwardTransportFramesToTheListener() throws Exception {
        manager = immediate();
        manager.start();

        StubRelay.Connection connection = relay.awaitConnection(TIMEOUT_MILLIS);
        assertNotNull(connection);
        assertNotNull(relay.awaitFrame(TIMEOUT_MILLIS));

        connection.send(new MessageBuilder()
                .setType(MessageType.PING)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned());

        assertNotNull("no reply to PING", relay.awaitFrame(TIMEOUT_MILLIS));
        assertTrue("a transport frame was handed to the listener: " + delivered,
                delivered.isEmpty());
    }

    /**
     * The relay has to be told the client is leaving, or it holds the session open until the
     * socket times out and keeps routing to a client that is gone.
     */
    @Test
    public void stopSaysGoodbyeToTheRelay() throws Exception {
        manager = immediate();
        manager.start();

        Message hello = relay.awaitFrame(TIMEOUT_MILLIS);
        assertNotNull(hello);
        assertEquals(MessageType.HELLO, hello.getType());

        manager.stop();

        Message farewell = relay.awaitFrame(TIMEOUT_MILLIS);
        assertNotNull("stop() never sent DISCONNECT", farewell);
        assertEquals(MessageType.DISCONNECT, farewell.getType());
        assertEquals("alice", farewell.getSenderId());
    }

    @Test
    public void stopLeavesTheConnectionDisconnected() throws Exception {
        manager = immediate();
        manager.start();
        assertNotNull(relay.awaitFrame(TIMEOUT_MILLIS));

        manager.stop();

        assertEquals(ConnectionState.DISCONNECTED, states.get(states.size() - 1));
    }

    /** Shutting down a client that never got as far as connecting is not an error. */
    @Test
    public void stopBeforeStartIsHarmless() {
        manager = immediate();
        manager.stop();
    }

    /** With nowhere to send it, an outbound message is dropped rather than thrown. */
    @Test
    public void sendWhileDisconnectedIsDropped() {
        manager = immediate();
        manager.sendMessage(new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .setPayload(new byte[]{1})
                .setIv(new byte[12])
                .setSignature(new byte[32])
                .build());
    }
}
