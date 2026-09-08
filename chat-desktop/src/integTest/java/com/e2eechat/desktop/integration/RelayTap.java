package com.e2eechat.desktop.integration;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.network.TlsSupport;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sits between the clients and the relay and records every frame that crosses.
 *
 * <p>This is how the harness asserts the central claim of the project: that a relay operator
 * cannot read what passes through. Rather than reaching inside the server for a hook, the tap
 * terminates TLS itself and forwards to the real relay over a second connection, so what it
 * records is exactly what a relay sees - the decoded frames it has to look at in order to route
 * them, and nothing more.
 *
 * <p>It presents the development certificate, which is the very certificate the clients pin. That
 * a tap can be built at all is not a flaw in this test; it is the documented weakness of the
 * development certificate, whose private key is reproducible by anyone. Against a deployment
 * pinning a real certificate this interception would fail at the handshake.
 */
final class RelayTap implements AutoCloseable {

    /** Where the development keystore sits relative to this module's working directory. */
    private static final String KEYSTORE = "../chat-server/src/main/resources/dev-keystore.p12";

    private final SSLServerSocket serverSocket;
    private final String relayHost;
    private final int relayPort;
    private final Thread acceptThread;

    /** Every frame seen in either direction, in arrival order. */
    private final List<Message> observed = new CopyOnWriteArrayList<>();

    /** Live sockets, so close() can tear the whole thing down. */
    private final List<SSLSocket> sockets = new CopyOnWriteArrayList<>();

    private volatile boolean running = true;

    RelayTap(String relayHost, int relayPort) throws Exception {
        this.relayHost = relayHost;
        this.relayPort = relayPort;

        File file = new File(KEYSTORE);
        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Development keystore not found at " + file.getAbsolutePath()
                            + ". Run scripts/generate-dev-cert.sh.");
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        KeyManagerFactory kmf;
        char[] password = TlsSupport.trustStorePassword();
        try {
            try (FileInputStream in = new FileInputStream(file)) {
                keyStore.load(in, password);
            }
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
        } finally {
            Arrays.fill(password, '\0');
        }

        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(kmf.getKeyManagers(), null, null);

        serverSocket = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 16, InetAddress.getLoopbackAddress());
        serverSocket.setEnabledProtocols(new String[]{"TLSv1.3"});

        acceptThread = new Thread(this::acceptLoop, "RelayTap-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** A snapshot of every frame the relay was in a position to read. */
    List<Message> observed() {
        return Collections.unmodifiableList(new ArrayList<>(observed));
    }

    private void acceptLoop() {
        while (running) {
            try {
                SSLSocket downstream = (SSLSocket) serverSocket.accept();
                downstream.startHandshake();
                sockets.add(downstream);

                SSLSocket upstream = TlsSupport.connectPinned(relayHost, relayPort);
                sockets.add(upstream);

                pump(downstream, upstream, "client-to-relay");
                pump(upstream, downstream, "relay-to-client");
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                // A client that goes away mid-handshake is normal; keep serving.
            }
        }
    }

    /** Copies frames one way, recording each. */
    private void pump(SSLSocket from, SSLSocket to, String name) throws Exception {
        FrameReader reader = new FrameReader(from.getInputStream());
        FrameWriter writer = new FrameWriter(to.getOutputStream());
        Thread thread = new Thread(() -> {
            try {
                while (running && !from.isClosed()) {
                    Message message = reader.readMessage();
                    observed.add(message);
                    writer.writeMessage(message);
                }
            } catch (Exception e) {
                // EOF once either end hangs up, which every teardown produces.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }, "RelayTap-" + name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(SSLSocket socket) {
        try {
            socket.close();
        } catch (Exception e) {
            // Tearing down a test fixture.
        }
    }

    @Override
    public void close() {
        running = false;
        for (SSLSocket socket : sockets) {
            closeQuietly(socket);
        }
        try {
            serverSocket.close();
        } catch (Exception e) {
            // Tearing down a test fixture.
        }
        acceptThread.interrupt();
    }
}
