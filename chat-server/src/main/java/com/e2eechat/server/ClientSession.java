package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.protocol.MessageCodec;
import com.e2eechat.core.util.Redact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

public class ClientSession implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ClientSession.class);
    private static final int IDLE_TIMEOUT_MS = 30000;
    private static final int QUEUE_CAPACITY = 256;

    private final Socket socket;
    private final ClientRegistry registry;
    private final ServerConfig config;
    private final Runnable onDisconnectTask;
    private final TokenBucket rateLimiter;
    
    private FrameReader in;
    private FrameWriter out;
    private String clientId;
    private int missedPings = 0;
    
    private final ArrayBlockingQueue<byte[]> outboundQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private Thread writerThread;
    private volatile boolean running = true;
    private boolean handshakeComplete = false;

    public ClientSession(Socket socket, ClientRegistry registry, ServerConfig config, Runnable onDisconnectTask) {
        this.socket = socket;
        this.registry = registry;
        this.config = config;
        this.onDisconnectTask = onDisconnectTask;
        this.rateLimiter = new TokenBucket(config.getRateLimitBurst(), config.getRateLimitRefillSec());
    }

    public ClientSession(Socket socket, ClientRegistry registry) {
        this(socket, registry, new ServerConfig(), () -> {});
    }

    public void enqueueFrame(byte[] frame) {
        if (!running) return;
        
        Metrics.updateQueueHighWaterMark(outboundQueue.size());
        
        if (!outboundQueue.offer(frame)) {
            Metrics.rejectedBufferOverflow.incrementAndGet();
            String redactedId = clientId != null ? Redact.id(clientId) : "unknown";
            logger.warn("buffer-overflow: id={}", redactedId);
            
            try {
                Message errorMsg = new MessageBuilder()
                        .setType(MessageType.ERROR)
                        .setSenderId("SERVER")
                        .setReceiverId(clientId != null ? clientId : "unknown")
                        .setMessageId(UUID.randomUUID().toString())
                        .setPayload("BUFFER_OVERFLOW".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned();
                byte[] errorFrame = MessageCodec.encode(errorMsg);
                if (out != null) {
                    out.writeFrame(errorFrame);
                }
            } catch (Exception e) {
                // Ignore
            }
            disconnect();
        }
    }

    public void sendMessage(Message message) {
        try {
            byte[] frame = MessageCodec.encode(message);
            enqueueFrame(frame);
        } catch (Exception e) {
            logger.error("error encoding frame for {}", clientId != null ? Redact.id(clientId) : "unknown");
        }
    }

    public void disconnect() {
        if (!running) return;
        
        int attempts = 0;
        while (!outboundQueue.isEmpty() && attempts < 20) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
            attempts++;
        }

        try { Thread.sleep(50); } catch (InterruptedException e) {} // Give writer time to flush
        
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Ignored
        }
        
        if (onDisconnectTask != null) {
            onDisconnectTask.run();
        }
    }

    private void startWriterThread() {
        writerThread = new Thread(() -> {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    byte[] frame = outboundQueue.take();
                    if (out != null) {
                        out.writeFrame(frame);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // Ignore writer thread IO errors silently to prevent log spam
            } finally {
                disconnect();
            }
        });
        writerThread.setName("Writer-" + (clientId != null ? clientId : "Init"));
        writerThread.start();
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(config.getHandshakeTimeoutMs());
            out = new FrameWriter(socket.getOutputStream());
            in = new FrameReader(socket.getInputStream());
            
            startWriterThread();

            while (running) {
                byte[] frame;
                try {
                    frame = in.readFrame();
                    missedPings = 0;
                } catch (SocketTimeoutException e) {
                    if (!handshakeComplete) {
                        logger.warn("timeout: id=unknown phase=handshake ip={}", socket.getInetAddress().getHostAddress());
                        break;
                    }
                    
                    missedPings++;
                    if (missedPings >= 2) {
                        logger.info("timeout: id={}", Redact.id(clientId));
                        break;
                    }
                    
                    Message pingMsg = new MessageBuilder()
                            .setType(MessageType.PING)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    sendMessage(pingMsg);
                    continue;
                }

                Message message = MessageCodec.decode(frame);
                
                // Rate Limiting Check
                if (message.getType() != MessageType.PING && message.getType() != MessageType.PONG) {
                    if (!rateLimiter.tryConsume()) {
                        Metrics.rejectedRateLimit.incrementAndGet();
                        String redactedId = clientId != null ? Redact.id(clientId) : "unknown";
                        logger.warn("rate-limited: id={}", redactedId);
                        Message errorMsg = new MessageBuilder()
                                .setType(MessageType.ERROR)
                                .setSenderId("SERVER")
                                .setReceiverId(clientId != null ? clientId : "unknown")
                                .setMessageId(UUID.randomUUID().toString())
                                .setPayload("RATE_LIMIT_EXCEEDED".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                .setTimestamp(System.currentTimeMillis())
                                .buildUnsigned();
                        sendMessage(errorMsg);
                        break; // Disconnect immediately
                    }
                }

                if (message.getType() == MessageType.HELLO) {
                    if (clientId == null) {
                        clientId = message.getSenderId();
                        if (writerThread != null) writerThread.setName("Writer-" + Redact.id(clientId));
                        boolean registered = registry.register(clientId, this);
                        if (!registered) {
                            logger.warn("reject-duplicate: id={}", Redact.id(clientId));
                            Message errorMsg = new MessageBuilder()
                                    .setType(MessageType.ERROR)
                                    .setSenderId("SERVER")
                                    .setReceiverId(clientId)
                                    .setMessageId(UUID.randomUUID().toString())
                                    .setPayload("Duplicate client ID".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .setTimestamp(System.currentTimeMillis())
                                    .buildUnsigned();
                            sendMessage(errorMsg);
                            break;
                        }
                        
                        handshakeComplete = true;
                        socket.setSoTimeout(IDLE_TIMEOUT_MS);
                        logger.info("hello: id={}", Redact.id(clientId));
                    }
                } else if (message.getType() == MessageType.DISCONNECT) {
                    logger.info("disconnect: id={}", Redact.id(clientId));
                    break;
                } else if (message.getType() == MessageType.PING) {
                    Message pongMsg = new MessageBuilder()
                            .setType(MessageType.PONG)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    sendMessage(pongMsg);
                } else if (message.getType() == MessageType.PONG) {
                    // Ignored, missedPings is reset
                } else {
                    if (!handshakeComplete) {
                        logger.warn("reject-no-handshake: ip={}", socket.getInetAddress().getHostAddress());
                        break;
                    }
                    routeFrame(message.getReceiverId(), frame);
                }
            }
        } catch (Exception e) {
            String redactedId = clientId != null ? Redact.id(clientId) : "unknown";
            logger.info("disconnect: id={} reason={}", redactedId, e.getClass().getSimpleName());
        } finally {
            if (clientId != null) {
                registry.unregister(clientId, this);
            }
            disconnect();
        }
    }

    private void routeFrame(String receiverId, byte[] frame) {
        if (receiverId == null) {
            return;
        }

        ClientSession receiverSession = registry.lookup(receiverId);
        if (receiverSession != null) {
            Metrics.messagesRouted.incrementAndGet();
            receiverSession.enqueueFrame(frame);
        } else {
            try {
                Message errorMsg = new MessageBuilder()
                        .setType(MessageType.ERROR)
                        .setSenderId("SERVER")
                        .setReceiverId(clientId)
                        .setMessageId(UUID.randomUUID().toString())
                        .setPayload("RECIPIENT_OFFLINE".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setTimestamp(System.currentTimeMillis())
                        .buildUnsigned();
                sendMessage(errorMsg);
            } catch (Exception e) {
                // Ignore
            }
        }
    }
}
