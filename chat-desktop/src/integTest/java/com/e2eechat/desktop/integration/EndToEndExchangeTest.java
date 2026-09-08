package com.e2eechat.desktop.integration;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.network.TlsSupport;
import com.e2eechat.core.protocol.MessageCodec;
import com.e2eechat.server.ChatServer;
import com.e2eechat.server.ServerConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * INTEG-01. Two client cores, a live relay, and a full conversation between them.
 *
 * <p>Nothing is stubbed. The relay is the shipped {@link ChatServer} on an ephemeral port, the
 * clients are the shipped {@link com.e2eechat.desktop.ChatClient} with the window left off, and
 * every frame crosses two real TLS connections. {@link RelayTap} sits in the middle recording what
 * the relay is in a position to read, which is what makes the last assertion here meaningful: two
 * hundred messages go through, and not one plaintext body is anywhere in the traffic.
 */
public class EndToEndExchangeTest {

    /** Messages sent in each direction. */
    private static final int MESSAGE_COUNT = 100;

    private static final long CONNECT_TIMEOUT_MILLIS = 20000;
    private static final long HANDSHAKE_TIMEOUT_MILLIS = 20000;
    private static final long DELIVERY_TIMEOUT_MILLIS = 60000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ChatServer relay;
    private RelayTap tap;
    private HeadlessClient alice;
    private HeadlessClient bob;

    @Before
    public void setUp() throws Exception {
        System.clearProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        TlsSupport.resetCachedContext();

        ServerConfig config = new ServerConfig();
        config.setPort(0);
        // Two clients exchanging two hundred messages as fast as they can is not abuse, and the
        // rate limiter is SERVER-04's subject, not this harness's.
        config.setRateLimitBurst(5000);
        config.setMaxConnections(50);
        config.setMaxConnectionsPerIp(50);

        relay = new ChatServer(config);
        Thread relayThread = new Thread(relay::start, "Relay");
        relayThread.setDaemon(true);
        relayThread.start();

        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
        while (relay.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("the relay never bound a port", relay.getPort() != 0);

        tap = new RelayTap("127.0.0.1", relay.getPort());

        alice = new HeadlessClient(tmp.newFolder("alice"), "Alice");
        bob = new HeadlessClient(tmp.newFolder("bob"), "Bob");
    }

    @After
    public void tearDown() {
        if (alice != null) {
            alice.close();
        }
        if (bob != null) {
            bob.close();
        }
        if (tap != null) {
            tap.close();
        }
        if (relay != null) {
            relay.shutdown();
        }
    }

    @Test
    public void twoClientsExchangeAHundredMessagesEachWayAndTheRelayLearnsNothing() throws Exception {
        // --- connect ------------------------------------------------------
        //
        // Bob first, and not until his opening HELLO has reached the relay does Alice connect at
        // all. The relay acknowledges nothing after that HELLO, so there is no signal a client can
        // wait on to know it has become routable; connecting the two together leaves a real window
        // in which Alice's handshake arrives before Bob is registered and is answered with
        // RECIPIENT_OFFLINE. Ordering it this way closes the window rather than sleeping through
        // it: Bob is registered before Alice opens a socket, and Alice's own registration travels
        // ahead of her handshake on the same ordered connection.
        bob.connect("127.0.0.1", tap.port());
        bob.awaitConnected(CONNECT_TIMEOUT_MILLIS);
        awaitFramesAtRelay(MessageType.HELLO, 1, CONNECT_TIMEOUT_MILLIS);

        alice.connect("127.0.0.1", tap.port());
        alice.awaitConnected(CONNECT_TIMEOUT_MILLIS);

        // --- handshake ----------------------------------------------------
        try {
            alice.establishSessionWith(bob.id(), HANDSHAKE_TIMEOUT_MILLIS);
            bob.awaitSession(alice.id(), HANDSHAKE_TIMEOUT_MILLIS);
        } catch (IllegalStateException e) {
            throw new AssertionError(e.getMessage() + "; the relay saw " + tapSummary(), e);
        }

        // --- a hundred messages each way ----------------------------------
        List<String> fromAlice = new ArrayList<>();
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            String text = "alice-to-bob-" + i + "-" + body(i);
            assertNotNull("alice refused to send message " + i, alice.send(text));
            fromAlice.add(text);
        }
        bob.awaitReceived(MESSAGE_COUNT, DELIVERY_TIMEOUT_MILLIS);

        List<String> fromBob = new ArrayList<>();
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            String text = "bob-to-alice-" + i + "-" + body(i);
            assertNotNull("bob refused to send message " + i, bob.send(text));
            fromBob.add(text);
        }
        alice.awaitReceived(MESSAGE_COUNT, DELIVERY_TIMEOUT_MILLIS);

        // --- verify -------------------------------------------------------
        assertEquals("bob did not receive every message alice sent",
                fromAlice, new ArrayList<>(bob.receivedText()));
        assertEquals("alice did not receive every message bob sent",
                fromBob, new ArrayList<>(alice.receivedText()));

        List<Message> observed = tap.observed();
        assertTrue("the conversation did not go through the relay: " + observed.size() + " frames",
                observed.size() >= 2 * MESSAGE_COUNT);
        assertTrue("the relay never saw a handshake",
                countOf(observed, MessageType.KEY_EXCHANGE_INIT) >= 1);
        assertTrue("the relay never saw a HELLO",
                countOf(observed, MessageType.HELLO) >= 2);
        assertTrue("the relay did not route two hundred text messages",
                countOf(observed, MessageType.TEXT_MESSAGE) >= 2 * MESSAGE_COUNT);

        // The claim the whole project rests on: the operator routing this traffic cannot read it.
        byte[] everythingTheRelaySaw = encodeAll(observed);

        // Before trusting that claim, prove the search would find plaintext if it were there. The
        // display names in HELLO are deliberately in the clear, so they must be found - if they
        // are not, the assertions below are passing on an empty haystack and mean nothing. This is
        // also an honest reminder of what the relay does learn: see the metadata section of
        // docs/security.md.
        assertTrue("the tap read nothing, so the leak check below would pass vacuously",
                indexOf(everythingTheRelaySaw, "Alice".getBytes(StandardCharsets.UTF_8)) >= 0);
        assertTrue("the tap read nothing, so the leak check below would pass vacuously",
                indexOf(everythingTheRelaySaw, "Bob".getBytes(StandardCharsets.UTF_8)) >= 0);
        for (String plaintext : fromAlice) {
            assertPlaintextAbsent(everythingTheRelaySaw, plaintext);
        }
        for (String plaintext : fromBob) {
            assertPlaintextAbsent(everythingTheRelaySaw, plaintext);
        }

        // --- disconnect ---------------------------------------------------
        alice.disconnect();
        bob.disconnect();

        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
        while (countOf(tap.observed(), MessageType.DISCONNECT) < 2
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("both clients should have said goodbye to the relay",
                2, countOf(tap.observed(), MessageType.DISCONNECT));
    }

    /** Blocks until the relay has been sent at least {@code count} frames of {@code type}. */
    private void awaitFramesAtRelay(MessageType type, int count, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (countOf(tap.observed(), type) < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("the relay never saw " + count + " " + type + " frames; it saw " + tapSummary(),
                countOf(tap.observed(), type) >= count);
    }

    /** What crossed the relay, by frame type, for diagnosing a handshake that did not complete. */
    private String tapSummary() {
        java.util.Map<MessageType, Integer> counts = new java.util.EnumMap<>(MessageType.class);
        for (Message message : tap.observed()) {
            counts.merge(message.getType(), 1, Integer::sum);
        }
        return counts.toString();
    }

    /** Padding, so a frame carries enough text that a partial leak would still be caught. */
    private static String body(int index) {
        StringBuilder text = new StringBuilder("the quick brown fox jumps over the lazy dog ");
        for (int i = 0; i < 4; i++) {
            text.append("filler").append(index).append(' ');
        }
        return text.toString();
    }

    private static int countOf(List<Message> messages, MessageType type) {
        int count = 0;
        for (Message message : messages) {
            if (message.getType() == type) {
                count++;
            }
        }
        return count;
    }

    /** Every observed frame back in its wire form, concatenated. */
    private static byte[] encodeAll(List<Message> messages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Message message : messages) {
            out.write(MessageCodec.encode(message));
        }
        return out.toByteArray();
    }

    private static void assertPlaintextAbsent(byte[] haystack, String plaintext) {
        byte[] needle = plaintext.getBytes(StandardCharsets.UTF_8);
        assertTrue("plaintext reached the relay: \"" + plaintext + "\"",
                indexOf(haystack, needle) < 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
