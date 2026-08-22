package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

public class FrameReader {
    private final DataInputStream dis;
    public static final int MAX_FRAME_BYTES = 1048576; // 1 MiB

    public FrameReader(InputStream is) {
        this.dis = new DataInputStream(is);
    }

    public Message readMessage() throws IOException, ProtocolException {
        byte[] payload = readFrame();
        return MessageCodec.decode(payload);
    }

    public byte[] readFrame() throws IOException, ProtocolException {
        int length = dis.readInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new ProtocolException("Invalid frame length: " + length);
        }
        byte version = dis.readByte();
        if (version != 1) {
            throw new ProtocolException("Unsupported frame version: " + version);
        }
        byte[] payload = new byte[length];
        dis.readFully(payload);
        return payload;
    }
}

