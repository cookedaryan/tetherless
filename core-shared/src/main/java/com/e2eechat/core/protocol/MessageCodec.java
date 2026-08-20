package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MessageCodec {

    public static byte[] encode(Message msg) throws ProtocolException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(msg.getType().ordinal());
            dos.writeInt(msg.getProtocolVersion());
            writeString(dos, msg.getMessageId());
            writeString(dos, msg.getSenderId());
            writeString(dos, msg.getReceiverId());
            dos.writeLong(msg.getTimestamp());
            writeByteArray(dos, msg.getIv());
            writeByteArray(dos, msg.getPayload());
            writeByteArray(dos, msg.getSignature());
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new ProtocolException("Failed to encode message", e);
        }
    }

    public static Message decode(byte[] data) throws ProtocolException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int wireCode = dis.readInt();
            MessageType type;
            try {
                type = MessageType.values()[wireCode];
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new ProtocolException("Unknown wire code: " + wireCode);
            }

            int protocolVersion = dis.readInt();
            String messageId = readString(dis, 128);
            String senderId = readString(dis, 128);
            String receiverId = readString(dis, 128);
            long timestamp = dis.readLong();
            byte[] iv = readByteArray(dis, 12); if (iv != null && iv.length != 12) {
                throw new ProtocolException("IV must be exactly 12 bytes");
            }
            byte[] payload = readByteArray(dis, 65536); // 64 KiB
            byte[] signature = readByteArray(dis, 512);

            return new MessageBuilder()
                    .setType(type)
                    .setProtocolVersion(protocolVersion)
                    .setMessageId(messageId)
                    .setSenderId(senderId)
                    .setReceiverId(receiverId)
                    .setTimestamp(timestamp)
                    .setIv(iv)
                    .setPayload(payload)
                    .setSignature(signature)
                    .buildUnsigned();
        } catch (IllegalArgumentException | IOException e) {
            throw new ProtocolException("Failed to decode message", e);
        }
    }

    private static void writeString(DataOutputStream dos, String s) throws IOException {
        if (s == null) {
            dos.writeInt(-1);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            dos.writeInt(bytes.length);
            dos.write(bytes);
        }
    }

    private static String readString(DataInputStream dis, int maxLength) throws IOException, ProtocolException {
        int length = dis.readInt();
        if (length == -1) { return null; }
        if (length < 0 || length > maxLength) {
            throw new ProtocolException("String length " + length + " exceeds limit " + maxLength);
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeByteArray(DataOutputStream dos, byte[] b) throws IOException {
        if (b == null) {
            dos.writeInt(-1);
        } else {
            dos.writeInt(b.length);
            dos.write(b);
        }
    }

    private static byte[] readByteArray(DataInputStream dis, int maxLength) throws IOException, ProtocolException {
        int length = dis.readInt();
        if (length == -1) { return null; }
        if (length < 0 || length > maxLength) {
            throw new ProtocolException("Byte array length " + length + " exceeds limit " + maxLength);
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return bytes;
    }
}

