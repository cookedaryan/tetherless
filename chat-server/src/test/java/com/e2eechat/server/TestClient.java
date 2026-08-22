package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.Assert.fail;

public class TestClient {
    private Socket socket;
    private FrameReader in;
    private FrameWriter out;
    private final String clientId;

    public TestClient(String clientId) {
        this.clientId = clientId;
    }

    public void connect(int port) throws IOException {
        socket = new Socket("localhost", port);
        in = new FrameReader(socket.getInputStream());
        out = new FrameWriter(socket.getOutputStream());
    }

    public void setReceiveBufferSize(int size) throws IOException {
        socket.setReceiveBufferSize(size);
    }
    
    public void setSoTimeout(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
    }

    public void sendHello() throws Exception {
        Message hello = new MessageBuilder()
                .setType(MessageType.HELLO)
                .setSenderId(clientId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .buildUnsigned();
        out.writeMessage(hello);
    }

    public void sendText(String receiverId, String text) throws Exception {
        Message textMsg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setSenderId(clientId)
                .setReceiverId(receiverId)
                .setMessageId(UUID.randomUUID().toString())
                .setTimestamp(System.currentTimeMillis())
                .setPayload(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setIv(new byte[12])
                .setSignature(new byte[32])
                .build();
        out.writeMessage(textMsg);
    }

    public void sendRawBytes(byte[] raw) throws IOException {
        socket.getOutputStream().write(raw);
        socket.getOutputStream().flush();
    }

    public Message awaitMessage(long timeoutMs) throws Exception {
        socket.setSoTimeout((int) timeoutMs);
        try {
            return in.readMessage();
        } catch (SocketTimeoutException e) {
            return null; // Return null on timeout instead of failing immediately, letting caller handle it
        }
    }

    public void assertDisconnected() {
        try {
            socket.setSoTimeout(5000);
            in.readMessage();
            fail("Expected socket to be disconnected, but successfully read a message");
        } catch (SocketTimeoutException e) {
            fail("Socket didn't disconnect within timeout");
        } catch (Exception e) {
            // Expected EOF or SocketException
        }
    }

    public void close() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // Ignored
        }
    }
    
    public String getClientId() {
        return clientId;
    }
}
