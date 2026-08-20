package com.e2eechat.server;

import com.e2eechat.core.models.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger; import org.slf4j.LoggerFactory; public class ChatServer { private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);
    private static final int PORT = 8080;
    private final ConcurrentHashMap<String, ObjectOutputStream> clients = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logger.info("Relay Server started on port {}", PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            logger.error("Error", e);
        }
    }

    private @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("SECOBDES")
    class ClientHandler implements Runnable {
        private final Socket socket;
        private ObjectInputStream in;
        private ObjectOutputStream out;
        private String clientId;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                while (true) {
                    Message message = (Message) in.readObject();
                    
                    if (message.getType() == com.e2eechat.core.models.MessageType.HELLO) {
                        clientId = message.getSenderId();
                        clients.put(clientId, out);
                        logger.info("Client connected: {}", com.e2eechat.core.util.Redact.id(clientId));
                    } else if (message.getType() == com.e2eechat.core.models.MessageType.DISCONNECT) {
                        break;
                    } else {
                        routeMessage(message);
                    }
                }
            } catch (Exception e) {
                logger.info("Client disconnected or error: {}", com.e2eechat.core.util.Redact.id(clientId));
            } finally {
                if (clientId != null) {
                    clients.remove(clientId);
                    logger.info("Client disconnected: {}", com.e2eechat.core.util.Redact.id(clientId));
                }
                try { socket.close(); } catch (IOException e) {}
            }
        }
        
        private void routeMessage(Message message) throws IOException {
            String receiverId = message.getReceiverId();
            ObjectOutputStream receiverOut = clients.get(receiverId);
            if (receiverOut != null) {
                receiverOut.writeObject(message);
                receiverOut.flush();
                
            } else {
                logger.warn("Receiver not found: {}", com.e2eechat.core.util.Redact.id(receiverId));
            }
        }
    }
}
