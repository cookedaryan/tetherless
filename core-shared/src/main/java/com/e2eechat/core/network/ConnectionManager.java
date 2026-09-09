package com.e2eechat.core.network;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.protocol.HelloPayload;
import com.e2eechat.core.protocol.MessageSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.security.KeyPair;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    /** Ceiling on the retry delay. The schedule is flat once it is reached. */
    static final long MAX_BACKOFF_MILLIS = 60000;

    /** Highest attempt number that still doubles the ceiling. */
    static final int MAX_ATTEMPT = 10;

    /**
     * How long a connection must stand up before it counts as healthy.
     *
     * <p>Below this, a drop is treated as another failed attempt. A relay that accepts the
     * handshake and then immediately closes - one that is overloaded, half-started, or shedding
     * load - would otherwise reset the backoff on every cycle and be met with an unthrottled
     * reconnect loop.
     */
    static final long STABLE_CONNECTION_MILLIS = 10000;

    /** How long {@link #stop()} waits for the writer to stand down before it writes the farewell. */
    private static final long WRITER_SHUTDOWN_MILLIS = 200;

    /** How long the relay is given to acknowledge a registration before the attempt is abandoned. */
    private static final long REGISTRATION_TIMEOUT_MILLIS = 10000;
    
    protected final String host;
    protected final int port;
    protected final String clientId;
    protected final MessageListener listener;

    /**
     * The identity keypair, used only to prove ownership of {@link #clientId} to the relay.
     *
     * <p>Nothing else on this connection is signed here - peer traffic is signed by
     * {@code SecureChat} before it ever reaches the queue.
     */
    protected final KeyPair identityKey;
    
    /**
     * Everything one connection attempt owns.
     *
     * <p>These were fields on the manager, shared by every attempt in turn. {@link
     * #connectInternal()} is the first path that throws after the reader thread has started, and
     * the reconnect loop only joins the reader on the success path - so a reader that outlived its
     * own attempt went back round its loop reading whatever {@code in} pointed at by then, which
     * was the <em>next</em> connection's stream. Two readers on one {@link FrameReader}, and a
     * reader able to close a live socket and release a registration wait it had nothing to do
     * with. Handing each attempt its own is what makes an abandoned one harmless: it can only ever
     * finish off the connection it was started for.
     */
    private static final class Attempt {
        SSLSocket socket;
        FrameReader in;
        FrameWriter out;
        Thread reader;
        Thread writer;

        /**
         * Released when the relay acknowledges this attempt's registration, and also when it ends
         * without one, so a dead socket is not waited out for the full timeout.
         */
        final CountDownLatch registered = new CountDownLatch(1);

        /** True only when an acknowledgement actually arrived, as opposed to the wait ending. */
        volatile boolean confirmed;

        /** Closes this attempt's socket and stands its writer down. Idempotent. */
        void close() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // Ignored
            }
            if (writer != null) {
                writer.interrupt();
            }
        }
    }

    /** The most recent attempt. Read by {@link #stop()} and {@link #waitForDisconnect()}. */
    private volatile Attempt attempt;

    private final BlockingQueue<Message> outboundQueue = new LinkedBlockingQueue<>();

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private ExecutorService reconnectExecutor;

    /**
     * @param clientId this client's peer id, which must be {@code PeerId.of(identityKey.getPublic())}
     * @param identityKey the identity keypair, used to sign the registration HELLO. Required: an
     *                    unauthenticated registration is what let anyone claim anyone else's id.
     */
    public ConnectionManager(String host, int port, String clientId, KeyPair identityKey,
                             MessageListener listener) {
        if (identityKey == null) {
            throw new IllegalArgumentException(
                    "An identity keypair is required to register with the relay");
        }
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.identityKey = identityKey;
        this.listener = listener;
    }

    private void updateState(ConnectionState newState) {
        if (this.state != newState) {
            this.state = newState;
            if (listener != null) {
                listener.onConnectionStateChanged(newState);
            }
        }
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            if (reconnectExecutor == null || reconnectExecutor.isShutdown()) {
                reconnectExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "Reconnect-Thread");
                    t.setDaemon(true);
                    return t;
                });
            }
            reconnectExecutor.submit(this::connectLoop);
        }
    }

    /**
     * Upper bound on the delay before retrying attempt {@code attempt}, in milliseconds.
     *
     * <p>Doubles per consecutive failure from one second, then holds at
     * {@link #MAX_BACKOFF_MILLIS}, so a relay that stays down does not accumulate an unbounded
     * retry rate against it.
     */
    static long backoffCeilingMillis(int attempt) {
        if (attempt >= MAX_ATTEMPT) {
            return MAX_BACKOFF_MILLIS;
        }
        return Math.min(1000L << attempt, MAX_BACKOFF_MILLIS);
    }

    /**
     * The delay actually waited before retrying, drawn uniformly from below the ceiling.
     *
     * <p>Full jitter rather than a fixed schedule: when a relay restarts, every client that was
     * attached to it retries at the same moment, and an undithered schedule would keep them
     * synchronised on every subsequent attempt as well.
     *
     * <p>Overridable so a test can drive the reconnect path without waiting out a real delay.
     */
    protected long backoffDelayMillis(int attempt) {
        return (long) (Math.random() * backoffCeilingMillis(attempt));
    }

    /**
     * How long to wait for the relay to acknowledge a registration.
     *
     * <p>Overridable so a test need not wait out a real timeout.
     */
    protected long registrationTimeoutMillis() {
        return REGISTRATION_TIMEOUT_MILLIS;
    }

    private void connectLoop() {
        int attempt = 0;

        while (running.get()) {
            long connectedAt = 0;
            try {
                updateState(attempt == 0
                        ? ConnectionState.CONNECTING : ConnectionState.RECONNECTING);

                connectInternal();

                updateState(ConnectionState.CONNECTED);
                connectedAt = System.currentTimeMillis();

                // Block until disconnected.
                waitForDisconnect();
            } catch (Exception e) {
                logger.warn("Connection attempt {} failed", attempt, e);
            }

            if (!running.get()) {
                break;
            }

            // Only a connection that stood up earns a clean slate. Backing off after a drop as
            // well as after a failure is the point: the drop path is the common one, and without
            // this the loop reconnects with no delay at all.
            if (connectedAt != 0
                    && System.currentTimeMillis() - connectedAt >= STABLE_CONNECTION_MILLIS) {
                attempt = 0;
            }

            long delay = backoffDelayMillis(attempt);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (attempt < MAX_ATTEMPT) {
                attempt++;
            }
        }
    }

    protected void connectInternal() throws Exception {
        // Its own socket, streams and registration latch, so nothing this attempt starts can
        // reach into the attempt that replaces it. The latch is armed by the constructor, before
        // the reader exists, so an acknowledgement cannot arrive with nothing to record it.
        final Attempt fresh = new Attempt();

        // Pins the relay's certificate and checks it was issued for this host; see TlsSupport.
        // This used to open the socket itself, and that copy was the one missing the hostname
        // check - which is the argument for there being a single way to dial the relay.
        fresh.socket = TlsSupport.connectPinned(host, port);

        fresh.in = new FrameReader(fresh.socket.getInputStream());
        fresh.out = new FrameWriter(fresh.socket.getOutputStream());
        attempt = fresh;

        // Start writer
        fresh.writer = new Thread(() -> writerLoop(fresh), "Client-Writer");
        fresh.writer.setDaemon(true);
        fresh.writer.start();

        // Start reader
        fresh.reader = new Thread(() -> readerLoop(fresh), "Client-Reader");
        fresh.reader.setDaemon(true);
        fresh.reader.start();
        
        outboundQueue.offer(registrationHello());

        // The socket being up is not the same as being reachable. Until the relay has registered
        // this id it will answer anything addressed here with RECIPIENT_OFFLINE, and a client that
        // called itself connected in that window would send a handshake into the gap and have it
        // refused. Waiting for the acknowledgement is what makes CONNECTED mean routable.
        fresh.registered.await(registrationTimeoutMillis(), TimeUnit.MILLISECONDS);
        if (!fresh.confirmed) {
            fresh.close();
            throw new IOException("The relay did not acknowledge the registration");
        }
    }

    /**
     * The frame that claims {@link #clientId} on the relay.
     *
     * <p>It carries the identity public key and is signed with the matching private key, which is
     * what the relay checks before it hands the id out. Registration used to be an unsigned frame
     * naming an id and nothing more: anyone who knew someone's id could connect, claim it, and
     * have the real owner refused with {@code ID_TAKEN} on their next login - a lockout of every
     * known user for the cost of one socket each.
     *
     * <p>The display name is deliberately left empty. The relay has no use for it and no business
     * knowing it; peers learn it from the {@code HELLO} they get end-to-end.
     */
    private Message registrationHello() throws Exception {
        Message hello = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(clientId)
                .setPayload(HelloPayload.encode(identityKey.getPublic(), ""))
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        return MessageSigner.sign(hello, identityKey.getPrivate());
    }

    private void writerLoop(Attempt own) {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Message msg = outboundQueue.take();
                own.out.writeMessage(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Writer error", e);
            own.close();
        }
    }

    /**
     * Hands a frame to the listener without letting it take the connection down with it.
     *
     * <p>The listener is application code on the reader thread, and the only handler above it is
     * the read loop's catch-all - which cannot tell a socket that has died from a frame the
     * application mishandled, so it treated both as the end of the connection and closed it. A
     * peer or relay that sent a frame the client parsed badly could hang it up on demand, over and
     * over. A bad frame is now a dropped frame; only the socket itself ends the loop.
     */
    private void deliver(Message msg) {
        if (listener == null) {
            return;
        }
        try {
            listener.onMessageReceived(msg);
        } catch (Exception e) {
            logger.error("Listener failed on a {} frame; dropping it and staying connected",
                    msg.getType(), e);
        }
    }

    private void readerLoop(Attempt own) {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Message msg = own.in.readMessage();
                if (msg.getType() == MessageType.PING) {
                    Message pong = new MessageBuilder()
                            .setType(MessageType.PONG)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    outboundQueue.offer(pong);
                } else if (msg.getType() == MessageType.DISCONNECT) {
                    logger.info("Server requested disconnect.");
                    own.close();
                    break;
                } else if (msg.getType() == MessageType.HELLO_ACK) {
                    // Between this client and the relay, like PING and DISCONNECT. It says the id
                    // is registered and the relay will route to it; it is not application traffic
                    // and never reaches the listener.
                    own.confirmed = true;
                    own.registered.countDown();
                } else {
                    deliver(msg);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                logger.warn("Reader error: {}", e.getMessage());
            }
            own.close();
        } finally {
            // This connection is over. If anything is still waiting to be told this client was
            // registered, it never will be, and waiting out the full timeout on a socket that is
            // already gone would only slow the retry down. It is this attempt's own wait, so an
            // abandoned reader releasing it cannot abort a healthy successor.
            own.registered.countDown();
        }
    }

    protected void waitForDisconnect() {
        Attempt current = attempt;
        try {
            if (current != null && current.reader != null) {
                current.reader.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Queues a frame for the relay.
     *
     * @return true when it was queued, false when there is no connection to put it on. The caller
     *         needs to know: a message that was dropped here and reported as sent is the
     *         difference between a red tick and a lie.
     */
    public boolean sendMessage(Message msg) {
        if (state == ConnectionState.CONNECTED) {
            return outboundQueue.offer(msg);
        }
        logger.warn("Cannot send message, state is {}", state);
        return false;
    }

    public void stop() {
        // Read before the state moves. This is what decides whether there is still a live
        // connection to say goodbye on, and updateState() below would already have answered no -
        // which is why the relay never used to be told that a client had left.
        boolean wasConnected = state == ConnectionState.CONNECTED;

        running.set(false);
        updateState(ConnectionState.DISCONNECTED);

        Attempt current = attempt;
        if (wasConnected && current != null && current.out != null) {
            // The writer loop is stopping and will not drain the queue, so the frame goes out on
            // this thread. Stand the writer down first so the two cannot interleave mid-frame.
            if (current.writer != null) {
                current.writer.interrupt();
                try {
                    current.writer.join(WRITER_SHUTDOWN_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                current.out.writeMessage(new MessageBuilder()
                        .setType(MessageType.DISCONNECT)
                        .setSenderId(clientId)
                        .setMessageId(UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned());
            } catch (Exception e) {
                logger.warn("Could not send DISCONNECT during shutdown", e);
            }
        }

        if (current != null) {
            current.close();
        }
        // Null when stop() is called on a manager that was never started.
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
    }
}
