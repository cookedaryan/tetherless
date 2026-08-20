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

    public synchronized void writeMessage(Message msg) throws IOException, ProtocolException {
        byte[] payload = MessageCodec.encode(msg);
        dos.writeInt(payload.length);
        dos.writeByte(FRAME_VERSION);
        dos.write(payload);
        dos.flush();
    }
}

