package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.network.TlsSupport;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A relay that speaks just enough of the wire protocol to exercise {@link
 * com.e2eechat.core.network.ConnectionManager}.
 *
 * <p>It is a real TLS 1.3 endpoint presenting the development certificate, not a mock, so the
 * client under test runs its production {@code connectInternal()} - handshake, framing and all.
 * The previous version of these tests stubbed that method out and left the socket it opened
 * unused, which meant nothing about connecting was actually covered.
 *
 * <p>It routes nothing. Frames that arrive are recorded for the test to assert on, and frames go
 * back only when a test sends them.
 */
final class StubRelay implements AutoCloseable {

    /** Where the development keystore sits relative to this module's working directory. */
    private static final String KEYSTORE = "../chat-server/src/main/resources/dev-keystore.p12";


    private final SSLServerSocket serverSocket;
    private final Thread acceptThread;

    /** Every frame received, across all connections, in arrival order. */
    private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();

    /** Connections accepted so far. Reconnect assertions read this. */
    private final AtomicInteger connections = new AtomicInteger();

    /** Handed to the test once a client has completed the handshake. */
    private final BlockingQueue<Connection> accepted = new LinkedBlockingQueue<>();

    private volatile Connection current;
    private volatile boolean closeImmediately;
    private volatile boolean running = true;

    StubRelay() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        KeyManagerFactory kmf;
        File file = new File(KEYSTORE);
        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Development keystore not found at " + file.getAbsolutePath()
                            + ". Run scripts/generate-dev-cert.sh.");
        }
        // Read from configuration rather than written here, so the relay opens the same keystore
        // the client pins even when a checkout overrides the development password.
        char[] password = TlsSupport.trustStorePassword();
        try {
            try (FileInputStream in = new FileInputStream(file)) {
                keyStore.load(in, password);
            }
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(kmf.getKeyManagers(), null, null);

        serverSocket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 16, InetAddress.getLoopbackAddress());
        serverSocket.setEnabledProtocols(new String[]{"TLSv1.3"});

        acceptThread = new Thread(this::acceptLoop, "StubRelay-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** Connections accepted since the relay started. */
    int connectionCount() {
        return connections.get();
    }

    /**
     * Makes the relay hang up the moment a client finishes the handshake.
     *
     * <p>This is the shape that used to send the client into an unthrottled reconnect loop.
     */
    void hangUpOnConnect(boolean enabled) {
        this.closeImmediately = enabled;
    }

    /** Waits for the next client to connect. */
    Connection awaitConnection(long timeoutMillis) throws InterruptedException {
        return accepted.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Waits for the next frame from any client, or null if none arrives in time. */
    Message awaitFrame(long timeoutMillis) throws InterruptedException {
        return received.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** Drops the live connection without warning, as a relay restart would. */
    void dropCurrentConnection() {
        Connection connection = current;
        if (connection != null) {
            connection.close();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                socket.startHandshake();
                connections.incrementAndGet();

                if (closeImmediately) {
                    socket.close();
                    continue;
                }

                Connection connection = new Connection(socket);
                current = connection;
                accepted.offer(connection);
                connection.startReading();
            } catch (Exception e) {
                if (running) {
                    // A client that goes away mid-handshake is normal here; keep serving.
                    continue;
                }
                return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        Connection connection = current;
        if (connection != null) {
            connection.close();
        }
        try {
            serverSocket.close();
        } catch (Exception e) {
            // Closing a test fixture.
        }
        acceptThread.interrupt();
    }

    /** One accepted client. */
    final class Connection {
        private final SSLSocket socket;
        private final FrameWriter out;
        private final FrameReader in;

        private Connection(SSLSocket socket) throws Exception {
            this.socket = socket;
            this.out = new FrameWriter(socket.getOutputStream());
            this.in = new FrameReader(socket.getInputStream());
        }

        private void startReading() {
            Thread reader = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        received.offer(in.readMessage());
                    }
                } catch (Exception e) {
                    // EOF when the client hangs up, which every teardown produces.
                }
            }, "StubRelay-Read");
            reader.setDaemon(true);
            reader.start();
        }

        /** Sends a frame to this client. */
        void send(Message message) throws Exception {
            out.writeMessage(message);
        }

        void close() {
            try {
                socket.close();
            } catch (Exception e) {
                // Closing a test fixture.
            }
        }
    }
}
