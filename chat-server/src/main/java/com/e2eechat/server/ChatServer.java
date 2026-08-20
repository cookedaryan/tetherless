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

public class ChatServer {
    private static final int PORT = 8080;
    private final ConcurrentHashMap<String, ObjectOutputStream> clients = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Relay Server started on port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ClientHandler implements Runnable {
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
                    
                    if (message.getType() == Message.MessageType.CONNECT) {
                        clientId = message.getSenderId();
                        clients.put(clientId, out);
                        System.out.println("Client connected: " + clientId);
                    } else if (message.getType() == Message.MessageType.DISCONNECT) {
                        break;
                    } else {
                        routeMessage(message);
                    }
                }
            } catch (Exception e) {
                System.out.println("Client disconnected or error: " + clientId);
            } finally {
                if (clientId != null) {
                    clients.remove(clientId);
                    System.out.println("Client disconnected: " + clientId);
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
                System.out.println("Routed message from " + message.getSenderId() + " to " + receiverId);
            } else {
                System.out.println("Receiver not found: " + receiverId);
            }
        }
    }
}
