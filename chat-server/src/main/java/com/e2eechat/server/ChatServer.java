package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.protocol.MessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {
    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);
    
    private final ServerConfig config;
    private final ClientRegistry registry;
    private final ExecutorService executorService;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> connectionsPerIp = new ConcurrentHashMap<>();

    private final MetricsServer metricsServer;

    public ChatServer() {
        this(new ServerConfig());
    }
    
    public ChatServer(int port) {
        ServerConfig cfg = new ServerConfig();
        cfg.setPort(port);
        this.config = cfg;
        this.registry = new ClientRegistry();
        this.executorService = Executors.newFixedThreadPool(config.getMaxConnections() * 2);
        this.metricsServer = new MetricsServer(port == 0 ? 0 : port + 1);
    }
    
    public ChatServer(ServerConfig config) {
        this.config = config;
        this.registry = new ClientRegistry();
        this.executorService = Executors.newFixedThreadPool(config.getMaxConnections() * 2);
        this.metricsServer = new MetricsServer(config.getPort() == 0 ? 0 : config.getPort() + 1);
    }

    private ServerSocket createSSLServerSocket() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream ksIs = getClass().getClassLoader().getResourceAsStream(config.getKeystorePath())) {
            if (ksIs == null) {
                // Try from file system
                try (FileInputStream fis = new FileInputStream(config.getKeystorePath())) {
                    keyStore.load(fis, config.getKeystorePassword().toCharArray());
                }
            } else {
                keyStore.load(ksIs, config.getKeystorePassword().toCharArray());
            }
        }
        
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, config.getKeystorePassword().toCharArray());
        
        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), null, null);
        
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) factory.createServerSocket(config.getPort());
        
        serverSocket.setEnabledProtocols(new String[]{"TLSv1.3"});
        
        // Restrict to AEAD cipher suites (TLS 1.3 standard ones)
        serverSocket.setEnabledCipherSuites(new String[]{
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256"
        });
        
        return serverSocket;
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        try {
            serverSocket = createSSLServerSocket();
            running = true;
            logger.info("Relay Server started on port {}", serverSocket.getLocalPort());
            
            metricsServer.start();

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    handleNewConnection(clientSocket);
                } catch (IOException e) {
                    if (running) {
                        logger.error("Error accepting client connection", e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to start server", e);
            throw new RuntimeException("Failed to start server", e);
        } finally {
            shutdown();
        }
    }
    
    private void handleNewConnection(Socket clientSocket) {
        String ip = clientSocket.getInetAddress().getHostAddress();
        
        // 1. Check max connections
        if (totalConnections.get() >= config.getMaxConnections()) {
            Metrics.rejectedServerFull.incrementAndGet();
            rejectConnection(clientSocket, "SERVER_FULL");
            return;
        }
        
        // 2. Check per-IP limit
        AtomicInteger ipCount = connectionsPerIp.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (ipCount.get() >= config.getMaxConnectionsPerIp()) {
            Metrics.rejectedTooManyConnections.incrementAndGet();
            rejectConnection(clientSocket, "TOO_MANY_CONNECTIONS");
            return;
        }
        
        // Accept
        totalConnections.incrementAndGet();
        ipCount.incrementAndGet();
        Metrics.connectedClients.incrementAndGet();
        
        logger.info("accept: ip={}", ip);
        
        Runnable cleanupTask = () -> {
            totalConnections.decrementAndGet();
            Metrics.connectedClients.decrementAndGet();
            AtomicInteger count = connectionsPerIp.get(ip);
            if (count != null) {
                if (count.decrementAndGet() <= 0) {
                    connectionsPerIp.remove(ip, count);
                }
            }
        };
        
        executorService.submit(new ClientSession(clientSocket, registry, config, cleanupTask));
    }
    
    private void rejectConnection(Socket clientSocket, String reason) {
        try {
            logger.warn("Rejecting connection from {}: {}", clientSocket.getInetAddress().getHostAddress(), reason);
            FrameWriter out = new FrameWriter(clientSocket.getOutputStream());
            Message errorMsg = new MessageBuilder()
                    .setType(MessageType.ERROR)
                    .setSenderId("SERVER")
                    .setReceiverId("unknown")
                    .setMessageId(UUID.randomUUID().toString())
                    .setPayload(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .setTimestamp(System.currentTimeMillis())
                    .buildUnsigned();
            out.writeFrame(MessageCodec.encode(errorMsg));
        } catch (Exception e) {
            // Ignore
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        logger.info("Shutting down ChatServer...");

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.warn("Error closing server socket", e);
            }
        }

        for (ClientSession session : registry.getAllSessions()) {
            try {
                Message disconnectMsg = new MessageBuilder()
                        .setType(MessageType.DISCONNECT)
                        .setSenderId("SERVER")
                        .setMessageId(UUID.randomUUID().toString())
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned();
                session.sendMessage(disconnectMsg);
            } catch (Exception e) {
                // Ignore errors during broadcast disconnect
            } finally {
                session.disconnect();
            }
        }

        executorService.shutdownNow();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate gracefully within 5 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (metricsServer != null) {
            metricsServer.stop();
        }
        
        logger.info("ChatServer shutdown complete.");
    }
    
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : config.getPort();
    }
    
    public int getMetricsPort() {
        return metricsServer != null ? metricsServer.getPort() : 0;
    }
}
