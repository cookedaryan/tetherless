package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Conformance against the committed wire-format vectors.
 *
 * <p>The Android instrumented suite runs the same assertions through
 * {@code ProtocolConformance}, so a divergence between the two platforms shows up as a failing
 * comparison against fixed bytes rather than as an intermittent handshake failure.
 */
public class ProtocolVectorsTest {

    private static final String BREAK =
            "\n\nThe wire format changed. Peers on the old build can no longer talk to peers on the"
            + "\nnew one. Bump protocolVersion and regenerate with ProtocolVectors.main() - do not"
            + "\nedit protocol-vectors.txt to make this pass.\n";

    @Test
    public void everyVectorEncodesToItsCommittedBytes() throws Exception {
        Map<String, String> expected = ProtocolVectors.expectedHex();

        for (ProtocolVectors.Vector vector : ProtocolVectors.all()) {
            String want = expected.get(vector.name);
            assertNotNull("no committed bytes for vector '" + vector.name
                    + "'; regenerate the resource", want);
            String got = ProtocolVectors.toHex(MessageCodec.encode(vector.message));
            assertEquals("vector '" + vector.name + "' encodes differently" + BREAK, want, got);
        }
    }

    @Test
    public void everyVectorDecodesBackToItsFields() throws Exception {
        Map<String, String> expected = ProtocolVectors.expectedHex();

        for (ProtocolVectors.Vector vector : ProtocolVectors.all()) {
            Message original = vector.message;
            Message decoded = MessageCodec.decode(ProtocolVectors.hex(expected.get(vector.name)));

            String at = "vector '" + vector.name + "': ";
            assertEquals(at + "type", original.getType(), decoded.getType());
            assertEquals(at + "protocolVersion",
                    original.getProtocolVersion(), decoded.getProtocolVersion());
            assertEquals(at + "messageId", original.getMessageId(), decoded.getMessageId());
            assertEquals(at + "senderId", original.getSenderId(), decoded.getSenderId());
            assertEquals(at + "receiverId", original.getReceiverId(), decoded.getReceiverId());
            assertEquals(at + "timestamp", original.getTimestamp(), decoded.getTimestamp());
            assertArrayEquals(at + "payload", original.getPayload(), decoded.getPayload());
            assertArrayEquals(at + "iv", original.getIv(), decoded.getIv());
            assertArrayEquals(at + "signature", original.getSignature(), decoded.getSignature());
        }
    }

    /** The committed set must actually cover the cases it claims to. */
    @Test
    public void vectorSetCoversTheAwkwardCases() {
        Map<String, String> expected = ProtocolVectors.expectedHex();
        for (String required : new String[]{
                "text-emoji", "text-rtl", "text-cjk", "text-combining",
                "text-empty-payload", "text-all-byte-values",
                "hello-no-receiver", "far-future-timestamp"}) {
            assertTrue("vector set is missing '" + required + "'", expected.containsKey(required));
        }
        assertEquals("every vector must have committed bytes",
                ProtocolVectors.all().size(), expected.size());
    }

    /** A null receiverId must survive the round trip as null, not as an empty string. */
    @Test
    public void nullReceiverStaysNull() throws Exception {
        Map<String, String> expected = ProtocolVectors.expectedHex();
        Message decoded = MessageCodec.decode(ProtocolVectors.hex(expected.get("hello-no-receiver")));
        assertEquals(null, decoded.getReceiverId());
    }

    /**
     * The shared checker the Android instrumented suite runs, exercised here too. If this passes on
     * the JVM and fails on Android, the platforms genuinely disagree - the checks themselves cannot
     * have drifted, because both call this same code.
     */
    @Test
    public void sharedConformanceCheckerPassesOnThisPlatform() {
        java.util.List<String> failures = ProtocolConformance.run();
        assertTrue("conformance failures on the JVM:\n  " + String.join("\n  ", failures),
                failures.isEmpty());
    }

    /** Re-encoding what we decoded must reproduce the same bytes. */
    @Test
    public void decodeThenEncodeIsStable() throws Exception {
        Map<String, String> expected = ProtocolVectors.expectedHex();
        for (ProtocolVectors.Vector vector : ProtocolVectors.all()) {
            String want = expected.get(vector.name);
            Message decoded = MessageCodec.decode(ProtocolVectors.hex(want));
            assertEquals("vector '" + vector.name + "' is not round-trip stable",
                    want, ProtocolVectors.toHex(MessageCodec.encode(decoded)));
        }
    }
}
