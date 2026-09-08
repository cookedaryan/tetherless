package com.e2eechat.core.network;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
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
    
    private SSLSocket socket;
    private FrameReader in;
    private FrameWriter out;
    
    private Thread readerThread;
    private Thread writerThread;
    private final BlockingQueue<Message> outboundQueue = new LinkedBlockingQueue<>();
    
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private ExecutorService reconnectExecutor;

    /**
     * Released when the relay acknowledges this connection's registration, and also when the
     * connection ends without one, so a dead socket is not waited out for the full timeout.
     */
    private volatile CountDownLatch registered;

    /** True only when an acknowledgement actually arrived, as opposed to the wait being released. */
    private volatile boolean registrationConfirmed;

    public ConnectionManager(String host, int port, String clientId, MessageListener listener) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
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
        // Pins the relay's development certificate. A production deployment would trust the system
        // CAs plus its own issuer instead; see TlsSupport for how the store is located.
        SSLSocketFactory factory = TlsSupport.clientContext().getSocketFactory();
        socket = (SSLSocket) factory.createSocket(host, port);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.startHandshake();
        
        in = new FrameReader(socket.getInputStream());
        out = new FrameWriter(socket.getOutputStream());

        // Armed before the reader starts, so an acknowledgement cannot arrive with nothing to
        // record it.
        registrationConfirmed = false;
        registered = new CountDownLatch(1);

        // Start writer
        writerThread = new Thread(this::writerLoop, "Client-Writer");
        writerThread.setDaemon(true);
        writerThread.start();
        
        // Start reader
        readerThread = new Thread(this::readerLoop, "Client-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
        
        // Send HELLO
        Message hello = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(clientId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        outboundQueue.offer(hello);

        // The socket being up is not the same as being reachable. Until the relay has registered
        // this id it will answer anything addressed here with RECIPIENT_OFFLINE, and a client that
        // called itself connected in that window would send a handshake into the gap and have it
        // refused. Waiting for the acknowledgement is what makes CONNECTED mean routable.
        registered.await(registrationTimeoutMillis(), TimeUnit.MILLISECONDS);
        if (!registrationConfirmed) {
            closeSocket();
            throw new IOException("The relay did not acknowledge the registration");
        }
    }

    private void writerLoop() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Message msg = outboundQueue.take();
                out.writeMessage(msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Writer error", e);
            closeSocket();
        }
    }

    private void readerLoop() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                Message msg = in.readMessage();
                if (msg.getType() == MessageType.PING) {
                    Message pong = new MessageBuilder()
                            .setType(MessageType.PONG)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    outboundQueue.offer(pong);
                } else if (msg.getType() == MessageType.DISCONNECT) {
                    logger.info("Server requested disconnect.");
                    closeSocket();
                    break;
                } else if (msg.getType() == MessageType.HELLO_ACK) {
                    // Between this client and the relay, like PING and DISCONNECT. It says the id
                    // is registered and the relay will route to it; it is not application traffic
                    // and never reaches the listener.
                    registrationConfirmed = true;
                    releaseRegistrationWait();
                } else {
                    if (listener != null) {
                        listener.onMessageReceived(msg);
                    }
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                logger.warn("Reader error: {}", e.getMessage());
            }
            closeSocket();
        } finally {
            // The connection is over. If anything is still waiting to be told this client was
            // registered, it never will be, and waiting out the full timeout on a socket that is
            // already gone would only slow the retry down.
            releaseRegistrationWait();
        }
    }

    /** Wakes whatever is waiting on the registration, confirmed or not. */
    private void releaseRegistrationWait() {
        CountDownLatch latch = registered;
        if (latch != null) {
            latch.countDown();
        }
    }

    protected void waitForDisconnect() {
        try {
            if (readerThread != null) {
                readerThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignored
        }
        if (writerThread != null) {
            writerThread.interrupt();
        }
    }

    public void sendMessage(Message msg) {
        if (state == ConnectionState.CONNECTED) {
            outboundQueue.offer(msg);
        } else {
            logger.warn("Cannot send message, state is {}", state);
        }
    }

    public void stop() {
        // Read before the state moves. This is what decides whether there is still a live
        // connection to say goodbye on, and updateState() below would already have answered no -
        // which is why the relay never used to be told that a client had left.
        boolean wasConnected = state == ConnectionState.CONNECTED;

        running.set(false);
        updateState(ConnectionState.DISCONNECTED);

        if (wasConnected && out != null) {
            // The writer loop is stopping and will not drain the queue, so the frame goes out on
            // this thread. Stand the writer down first so the two cannot interleave mid-frame.
            if (writerThread != null) {
                writerThread.interrupt();
                try {
                    writerThread.join(WRITER_SHUTDOWN_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                out.writeMessage(new MessageBuilder()
                        .setType(MessageType.DISCONNECT)
                        .setSenderId(clientId)
                        .setMessageId(UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned());
            } catch (Exception e) {
                logger.warn("Could not send DISCONNECT during shutdown", e);
            }
        }

        closeSocket();
        // Null when stop() is called on a manager that was never started.
        if (reconnectExecutor != null) {
            reconnectExecutor.shutdownNow();
        }
    }
}
