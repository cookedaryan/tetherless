package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;
import com.e2eechat.core.models.MessageType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The frozen wire-format conformance set: canonical {@link Message}s and the exact bytes they must
 * encode to.
 *
 * <h2>Why this lives in main rather than test</h2>
 * These vectors have to be asserted from an Android instrumented test as well as the JVM suite, and
 * an instrumented test runs on a device with no access to the repository. Shipping the definitions
 * and the expected bytes inside the {@code core-shared} artifact is what lets both platforms check
 * the same contract. Nothing in production reads this class.
 *
 * <h2>What it protects</h2>
 * R1 in the development plan: JVM and Android disagreeing about the wire format or key derivation.
 * The mitigations for that are written but were never verified across platforms, and the failure
 * mode is a message that decodes to subtly different fields - or a handshake that fails once every
 * few hundred attempts. Comparing against committed bytes turns that into an exact assertion.
 *
 * <h2>If a test using these fails</h2>
 * The encoding changed. That is a wire-format break: peers on the old build can no longer talk to
 * peers on the new one. Bump {@code protocolVersion} and regenerate with {@link #main(String[])},
 * rather than editing the resource to make the test pass.
 */
public final class ProtocolVectors {

    /** Committed expected bytes, one {@code name=hex} pair per line. */
    public static final String RESOURCE = "protocol-vectors.txt";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** A named message together with the bytes it must encode to. */
    public static final class Vector {
        public final String name;
        public final Message message;

        Vector(String name, Message message) {
            this.name = name;
            this.message = message;
        }
    }

    private ProtocolVectors() {
    }

    /**
     * Every case the format must survive. Deliberately fixed in every field - no clocks, no random
     * ids - so the encoding is reproducible byte for byte.
     */
    public static List<Vector> all() {
        List<Vector> out = new ArrayList<>();

        String alice = "fd40680148d245953ad5ce85a27f6235";
        String bob = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
        long ts = 1735689600000L;
        byte[] iv = hex("000000010000000000000001");
        byte[] sig = hex("30450221008f2c1d");

        out.add(v("text-ascii", MessageType.TEXT_MESSAGE, alice, bob,
                "Hello, End-to-End Encryption!".getBytes(StandardCharsets.UTF_8), iv,
                "00000000-0000-4000-8000-000000000001", ts, sig));

        // Multi-byte UTF-8 outside the BMP: the single most likely thing to differ between two
        // platforms' string handling.
        out.add(v("text-emoji", MessageType.TEXT_MESSAGE, alice, bob,
                "ok 👍🏽 done".getBytes(StandardCharsets.UTF_8), iv,
                "00000000-0000-4000-8000-000000000002", ts, sig));

        out.add(v("text-rtl", MessageType.TEXT_MESSAGE, alice, bob,
                "مرحبا shalom שלום"
                        .getBytes(StandardCharsets.UTF_8), iv,
                "00000000-0000-4000-8000-000000000003", ts, sig));

        out.add(v("text-cjk", MessageType.TEXT_MESSAGE, alice, bob,
                "你好世界".getBytes(StandardCharsets.UTF_8), iv,
                "00000000-0000-4000-8000-000000000004", ts, sig));

        // A lone combining mark and a zero-width joiner: normalisation differences would show here.
        out.add(v("text-combining", MessageType.TEXT_MESSAGE, alice, bob,
                "é å ‍".getBytes(StandardCharsets.UTF_8), iv,
                "00000000-0000-4000-8000-000000000005", ts, sig));

        out.add(v("text-empty-payload", MessageType.TEXT_MESSAGE, alice, bob,
                new byte[0], iv, "00000000-0000-4000-8000-000000000006", ts, sig));

        out.add(v("text-1kib-payload", MessageType.TEXT_MESSAGE, alice, bob,
                repeat((byte) 0x5A, 1024), iv,
                "00000000-0000-4000-8000-000000000007", ts, sig));

        // Every byte value, so no length or sign handling can quietly mangle one.
        out.add(v("text-all-byte-values", MessageType.TEXT_MESSAGE, alice, bob,
                allByteValues(), iv, "00000000-0000-4000-8000-000000000008", ts, sig));

        // HELLO has no receiver and no IV, and carries the v2 body shape.
        out.add(v("hello-no-receiver", MessageType.HELLO, alice, null,
                hex("0000000401020304000000024162"), null,
                "00000000-0000-4000-8000-000000000009", ts, null));

        out.add(v("key-exchange-init", MessageType.KEY_EXCHANGE_INIT, alice, bob,
                repeat((byte) 0x11, 64), null,
                "00000000-0000-4000-8000-00000000000a", ts, sig));

        out.add(v("key-exchange-reply", MessageType.KEY_EXCHANGE_REPLY, bob, alice,
                repeat((byte) 0x22, 64), null,
                "00000000-0000-4000-8000-00000000000b", ts, sig));

        out.add(v("delivery-ack", MessageType.DELIVERY_ACK, bob, alice,
                "00000000-0000-4000-8000-000000000001".getBytes(StandardCharsets.UTF_8), null,
                "00000000-0000-4000-8000-00000000000c", ts, sig));

        out.add(v("read-receipt", MessageType.READ_RECEIPT, bob, alice,
                new byte[0], null, "00000000-0000-4000-8000-00000000000d", ts, sig));

        out.add(v("typing-on", MessageType.TYPING, bob, alice,
                new byte[]{1}, null, "00000000-0000-4000-8000-00000000000e", ts, sig));

        out.add(v("error-recipient-offline", MessageType.ERROR, alice, bob,
                "RECIPIENT_OFFLINE".getBytes(StandardCharsets.UTF_8), null,
                "00000000-0000-4000-8000-00000000000f", ts, null));

        out.add(v("ping", MessageType.PING, alice, null,
                hex("0000000000000001"), null,
                "00000000-0000-4000-8000-000000000010", ts, null));

        // Timestamp beyond 32 bits, to catch anything treating it as an int.
        out.add(v("far-future-timestamp", MessageType.TEXT_MESSAGE, alice, bob,
                "later".getBytes(StandardCharsets.UTF_8), iv,
                "00000000-0000-4000-8000-000000000011", 4102444800000L, sig));

        return Collections.unmodifiableList(out);
    }

    private static Vector v(String name, MessageType type, String from, String to,
                            byte[] payload, byte[] iv, String messageId, long timestamp,
                            byte[] signature) {
        MessageBuilder b = new MessageBuilder()
                .setType(type)
                .setSenderId(from)
                .setReceiverId(to)
                .setPayload(payload)
                .setIv(iv)
                .setMessageId(messageId)
                .setTimestamp(timestamp)
                .setProtocolVersion(2);
        if (signature != null) {
            b.setSignature(signature);
        }
        return new Vector(name, b.buildUnsigned());
    }

    /** Expected bytes, keyed by vector name, read from the committed resource. */
    public static Map<String, String> expectedHex() {
        Map<String, String> out = new LinkedHashMap<>();
        InputStream in = ProtocolVectors.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException(RESOURCE + " is missing from the classpath. "
                    + "Regenerate it with ProtocolVectors.main().");
        }
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
        return out;
    }

    // ------------------------------------------------------------------- hex

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    public static byte[] hex(String s) {
        int len = s.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] repeat(byte value, int count) {
        byte[] out = new byte[count];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static byte[] allByteValues() {
        byte[] out = new byte[256];
        for (int i = 0; i < 256; i++) {
            out[i] = (byte) i;
        }
        return out;
    }

    /**
     * Regenerates the resource. Run deliberately, only when the wire format is being changed on
     * purpose and {@code protocolVersion} is being bumped with it:
     *
     * <pre>java -cp core-shared/build/classes/java/main \
     *   com.e2eechat.core.protocol.ProtocolVectors &gt; \
     *   core-shared/src/main/resources/protocol-vectors.txt</pre>
     */
    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Frozen wire-format vectors. Generated by ProtocolVectors.main().\n");
        sb.append("# A diff here is a wire-format break: bump protocolVersion, do not edit to\n");
        sb.append("# make a test pass. Asserted by both the JVM and Android suites.\n");
        for (Vector vector : all()) {
            sb.append(vector.name).append('=')
              .append(toHex(MessageCodec.encode(vector.message))).append('\n');
        }
        System.out.print(sb);
    }
}
