package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChatServer {
    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);
    private final int port;
    private final ClientRegistry registry = new ClientRegistry();
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public ChatServer() {
        this(8080);
    }
    
    public ChatServer(int port) {
        this.port = port;
    }

    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        try {
            serverSocket = new ServerSocket(port);
            running = true;
            logger.info("Relay Server started on port {}", serverSocket.getLocalPort());

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executorService.submit(new ClientSession(clientSocket, registry));
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

    public void shutdown() {
        if (!running) return;
        running = false;
        logger.info("Shutting down ChatServer...");

        // Close server socket to stop accepting new connections
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.warn("Error closing server socket", e);
            }
        }

        // Send DISCONNECT to all active sessions
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

        // Await executor termination
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
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }
}
