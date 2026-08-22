package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.protocol.MessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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

    public ChatServer() {
        this(new ServerConfig());
    }
    
    public ChatServer(int port) {
        ServerConfig cfg = new ServerConfig();
        cfg.setPort(port);
        this.config = cfg;
        this.registry = new ClientRegistry();
        this.executorService = Executors.newFixedThreadPool(config.getMaxConnections() * 2);
    }
    
    public ChatServer(ServerConfig config) {
        this.config = config;
        this.registry = new ClientRegistry();
        this.executorService = Executors.newFixedThreadPool(config.getMaxConnections() * 2);
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        try {
            serverSocket = new ServerSocket(config.getPort());
            running = true;
            logger.info("Relay Server started on port {}", serverSocket.getLocalPort());

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
        } catch (IOException e) {
            logger.error("Error starting server", e);
        } finally {
            shutdown();
        }
    }
    
    private void handleNewConnection(Socket clientSocket) {
        String ip = clientSocket.getInetAddress().getHostAddress();
        
        // 1. Check max connections
        if (totalConnections.get() >= config.getMaxConnections()) {
            rejectConnection(clientSocket, "SERVER_FULL");
            return;
        }
        
        // 2. Check per-IP limit
        AtomicInteger ipCount = connectionsPerIp.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (ipCount.get() >= config.getMaxConnectionsPerIp()) {
            rejectConnection(clientSocket, "TOO_MANY_CONNECTIONS");
            return;
        }
        
        // Accept
        totalConnections.incrementAndGet();
        ipCount.incrementAndGet();
        
        Runnable cleanupTask = () -> {
            totalConnections.decrementAndGet();
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
        
        logger.info("ChatServer shutdown complete.");
    }
    
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : config.getPort();
    }
}
