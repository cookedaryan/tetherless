package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;
import org.junit.Test;

import java.util.Random;


import static org.junit.Assert.fail;

public class CodecFuzzTest {
    private static final int ITERATIONS = 10000;
    private final Random random = new Random(42);

    @Test
    public void testRandomBytesDecode() {
        for (int i = 0; i < ITERATIONS; i++) {
            int length = random.nextInt(2048);
            byte[] fuzzData = new byte[length];
            random.nextBytes(fuzzData);

            try {
                MessageCodec.decode(fuzzData);
            } catch (ProtocolException e) {
            } catch (Exception e) {
                fail("Unexpected exception type: " + e.getClass().getName());
            }
        }
    }

    @Test
    public void testSingleBitMutationsDecode() throws ProtocolException {
        Message msg = new MessageBuilder()
                .setType(MessageType.TEXT_MESSAGE)
                .setMessageId("fuzz-msg-1")
                .setSenderId("fuzzer")
                .setReceiverId("target")
                .setTimestamp(System.currentTimeMillis())
                .setIv(new byte[12])
                .setPayload(new byte[]{1, 2, 3, 4, 5})
                .buildUnsigned();

        byte[] validFrame = MessageCodec.encode(msg);

        for (int i = 0; i < ITERATIONS; i++) {
            byte[] mutated = validFrame.clone();
            int bitToFlip = random.nextInt(validFrame.length * 8);
            int byteIndex = bitToFlip / 8;
            int bitIndex = bitToFlip % 8;
            mutated[byteIndex] = (byte) (mutated[byteIndex] ^ (1 << bitIndex));

            try {
                MessageCodec.decode(mutated);
            } catch (ProtocolException e) {
            } catch (Exception e) {
                fail("Unexpected exception type on bit flip: " + e.getClass().getName());
            }
        }
    }
}

