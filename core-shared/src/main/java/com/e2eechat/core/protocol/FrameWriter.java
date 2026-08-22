package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class FrameWriter {
    private final DataOutputStream dos;
    private static final byte FRAME_VERSION = 1;

    public FrameWriter(OutputStream os) {
        this.dos = new DataOutputStream(os);
    }

    public void writeMessage(Message msg) throws IOException, ProtocolException {
        byte[] payload = MessageCodec.encode(msg);
        writeFrame(payload);
    }

    public synchronized void writeFrame(byte[] payload) throws IOException {
        dos.writeInt(payload.length);
        dos.writeByte(FRAME_VERSION);
        dos.write(payload);
        dos.flush();
    }
}

