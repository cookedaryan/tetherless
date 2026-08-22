package com.e2eechat.desktop;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);
    
    private final String host;
    private final int port;
    private final String clientId;
    private final MessageListener listener;
    
    private SSLSocket socket;
    private FrameReader in;
    private FrameWriter out;
    
    private Thread readerThread;
    private Thread writerThread;
    private final BlockingQueue<Message> outboundQueue = new LinkedBlockingQueue<>();
    
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Reconnect-Thread");
        t.setDaemon(true);
        return t;
    });

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
            reconnectExecutor.submit(this::connectLoop);
        }
    }

    private void connectLoop() {
        int attempt = 0;
        final long MAX_BACKOFF = 60000;
        
        while (running.get()) {
            try {
                updateState(attempt == 0 ? ConnectionState.CONNECTING : ConnectionState.RECONNECTING);
                
                connectInternal();
                
                updateState(ConnectionState.CONNECTED);
                attempt = 0; // reset backoff on successful connect
                
                // Block until disconnected
                waitForDisconnect();
                
            } catch (Exception e) {
                if (!running.get()) break;
                
                long backoff = Math.min((1000L << attempt), MAX_BACKOFF);
                long jitter = (long) (Math.random() * backoff);
                logger.warn("Connection failed. Retrying in {} ms", jitter, e);
                
                try {
                    Thread.sleep(jitter);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                
                if (attempt < 10) attempt++;
            }
        }
    }

    private void connectInternal() throws Exception {
        // We assume dev-keystore.p12 for TLS pinning in local dev.
        // In a real production app, this would use the system default CAs + Let's Encrypt.
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream tsIs = getClass().getClassLoader().getResourceAsStream("dev-keystore.p12")) {
            if (tsIs == null) {
                try (FileInputStream fis = new FileInputStream("../chat-server/src/main/resources/dev-keystore.p12")) {
                    trustStore.load(fis, "changeit".toCharArray());
                } catch (Exception e) {
                    try (FileInputStream fis = new FileInputStream("src/main/resources/dev-keystore.p12")) {
                        trustStore.load(fis, "changeit".toCharArray());
                    }
                }
            } else {
                trustStore.load(tsIs, "changeit".toCharArray());
            }
        }
        
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(null, tmf.getTrustManagers(), null);
        
        SSLSocketFactory factory = sslContext.getSocketFactory();
        socket = (SSLSocket) factory.createSocket(host, port);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.startHandshake();
        
        in = new FrameReader(socket.getInputStream());
        out = new FrameWriter(socket.getOutputStream());
        
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
        }
    }

    private void waitForDisconnect() {
        try {
            if (readerThread != null) readerThread.join();
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
        if (writerThread != null) writerThread.interrupt();
    }

    public void sendMessage(Message msg) {
        if (state == ConnectionState.CONNECTED) {
            outboundQueue.offer(msg);
        } else {
            logger.warn("Cannot send message, state is {}", state);
        }
    }

    public void stop() {
        running.set(false);
        updateState(ConnectionState.DISCONNECTED);
        
        try {
            if (state == ConnectionState.CONNECTED && out != null) {
                Message disconnectMsg = new MessageBuilder()
                        .setType(MessageType.DISCONNECT)
                        .setSenderId(clientId)
                        .setMessageId(UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned();
                outboundQueue.offer(disconnectMsg);
                
                // wait up to 500ms for queue to drain
                int attempts = 0;
                while (!outboundQueue.isEmpty() && attempts < 50) {
                    Thread.sleep(10);
                    attempts++;
                }
                Thread.sleep(50);
            }
        } catch (Exception e) {
            logger.warn("Error sending DISCONNECT during shutdown", e);
        }
        
        closeSocket();
        reconnectExecutor.shutdownNow();
    }
}
