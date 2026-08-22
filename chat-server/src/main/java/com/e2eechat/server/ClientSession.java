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
    private static final int TIMEOUT_MS = 30000;
    private static final int QUEUE_CAPACITY = 256;

    private final Socket socket;
    private final ClientRegistry registry;
    
    private FrameReader in;
    private FrameWriter out;
    private String clientId;
    private int missedPings = 0;
    
    private final ArrayBlockingQueue<byte[]> outboundQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private Thread writerThread;
    private volatile boolean running = true;

    public ClientSession(Socket socket, ClientRegistry registry) {
        this.socket = socket;
        this.registry = registry;
    }

    public void enqueueFrame(byte[] frame) {
        if (!running) return;
        if (!outboundQueue.offer(frame)) {
            logger.warn("Outbound queue full for client {}, disconnecting to prevent blocking.", clientId != null ? Redact.id(clientId) : "unknown");
            
            try {
                Message errorMsg = new MessageBuilder()
                        .setType(MessageType.ERROR)
                        .setSenderId("SERVER")
                        .setReceiverId(clientId)
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
            logger.error("Failed to encode message", e);
        }
    }

    public void disconnect() {
        running = false;
        
        // Give writer thread a short time to flush the queue
        int attempts = 0;
        while (!outboundQueue.isEmpty() && attempts < 20) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
            attempts++;
        }

        if (writerThread != null) {
            writerThread.interrupt();
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Ignored
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
                logger.info("Writer thread IO error: {}", e.getMessage());
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
            socket.setSoTimeout(TIMEOUT_MS);
            out = new FrameWriter(socket.getOutputStream());
            in = new FrameReader(socket.getInputStream());
            
            startWriterThread();

            while (running) {
                byte[] frame;
                try {
                    frame = in.readFrame();
                    missedPings = 0; // Reset on any successful read
                } catch (SocketTimeoutException e) {
                    missedPings++;
                    if (missedPings >= 2) {
                        logger.info("Client {} timed out after {} missed pings", 
                                clientId != null ? Redact.id(clientId) : "unknown", missedPings);
                        break;
                    }
                    // Send PING
                    Message pingMsg = new MessageBuilder()
                            .setType(MessageType.PING)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    sendMessage(pingMsg);
                    continue;
                }

                // Decode only to inspect headers for routing
                Message message = MessageCodec.decode(frame);

                if (message.getType() == MessageType.HELLO) {
                    if (clientId == null) {
                        clientId = message.getSenderId();
                        if (writerThread != null) writerThread.setName("Writer-" + clientId);
                        boolean registered = registry.register(clientId, this);
                        if (!registered) {
                            Message errorMsg = new MessageBuilder()
                                    .setType(MessageType.ERROR)
                                    .setSenderId("SERVER")
                                    .setReceiverId(clientId)
                                    .setMessageId(UUID.randomUUID().toString())
                                    .setPayload("Duplicate client ID".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .setTimestamp(System.currentTimeMillis())
                                    .buildUnsigned();
                            sendMessage(errorMsg);
                            break; // Disconnect
                        }
                    } else {
                        logger.warn("Received duplicate HELLO from already identified client: {}", Redact.id(clientId));
                    }
                } else if (message.getType() == MessageType.DISCONNECT) {
                    break;
                } else if (message.getType() == MessageType.PING) {
                    Message pongMsg = new MessageBuilder()
                            .setType(MessageType.PONG)
                            .setMessageId(UUID.randomUUID().toString())
                            .setTimestamp(System.currentTimeMillis())
                            .buildUnsigned();
                    sendMessage(pongMsg);
                } else if (message.getType() == MessageType.PONG) {
                    // Handled by missedPings reset above
                } else {
                    routeFrame(message.getReceiverId(), frame);
                }
            }
        } catch (Exception e) {
            if (clientId != null) {
                logger.info("Client error or disconnect {}: {}", Redact.id(clientId), e.getMessage());
            } else {
                logger.info("Unknown client error or disconnect: {}", e.getMessage());
            }
        } finally {
            if (clientId != null) {
                registry.unregister(clientId, this);
            }
            disconnect();
        }
    }

    private void routeFrame(String receiverId, byte[] frame) {
        if (receiverId == null) {
            logger.warn("Message dropped (no receiver specified)");
            return;
        }

        ClientSession receiverSession = registry.lookup(receiverId);
        if (receiverSession != null) {
            receiverSession.enqueueFrame(frame);
        } else {
            logger.warn("Receiver not found: {}", Redact.id(receiverId));
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
