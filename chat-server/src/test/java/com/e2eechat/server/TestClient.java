package com.e2eechat.server;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import com.e2eechat.core.protocol.FrameReader;
import com.e2eechat.core.protocol.FrameWriter;
import com.e2eechat.core.network.TlsSupport;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.Assert.fail;

public class TestClient {
    private Socket socket;
    private FrameReader in;
    private FrameWriter out;

    /** The identity this client registers with; ids are derived from its key, not chosen. */
    private final TestIdentity identity;
    private final String clientId;

    /**
     * @param label a stable nickname for the identity, not the id itself. The relay only accepts a
     *              registration whose id is the hash of the key presented with it, so the id comes
     *              out of {@link TestIdentity} rather than being made up here.
     */
    public TestClient(String label) {
        this.identity = TestIdentity.named(label);
        this.clientId = identity.peerId();
    }

    /** This client's peer id, for tests that assert on routing. */
    public String peerId() {
        return clientId;
    }

    public void connectPlaintext(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        in = new FrameReader(socket.getInputStream());
        out = new FrameWriter(socket.getOutputStream());
    }

    public void connect(int port) throws Exception {
        // Pins the checked-in dev certificate; see TlsSupport for how the truststore is located.
        this.socket = TlsSupport.connectPinned("127.0.0.1", port);
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
        out.writeMessage(identity.registrationHello());
    }

    /** Registers under somebody else's id while signing with our own key. */
    public void sendHelloClaiming(String victimLabel) throws Exception {
        out.writeMessage(identity.registrationClaiming(TestIdentity.named(victimLabel).peerId()));
    }

    /**
     * Registers with the relay and waits for it to say the id is routable.
     *
     * <p>Replaces {@code sendHello()} followed by a hopeful sleep. The relay now acknowledges a
     * registration, so a test can wait for the thing it actually needs instead of guessing how
     * long it takes.
     */
    public void register() throws Exception {
        sendHello();
        Message ack = awaitMessage(5000);
        if (ack == null || ack.getType() != MessageType.HELLO_ACK) {
            fail("the relay did not acknowledge the registration for " + clientId
                    + "; received " + (ack == null ? "nothing" : ack.getType().toString()));
        }
    }

    /** @param receiverLabel the recipient's nickname, resolved to their peer id here. */
    public void sendText(String receiverLabel, String text) throws Exception {
        String receiverId = TestIdentity.named(receiverLabel).peerId();
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
