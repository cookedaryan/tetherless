package com.e2eechat.core.protocol;

import com.e2eechat.core.crypto.DHUtils;
import com.e2eechat.core.models.Message;

import javax.crypto.spec.DHPrivateKeySpec;
import javax.crypto.spec.DHPublicKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The cross-platform conformance check, in one place so the JVM and Android suites run identical
 * code rather than two drifting transcriptions of it.
 *
 * <p>Returns a list of human-readable failures; empty means this platform agrees with the frozen
 * vectors. It lives in main for the same reason as {@link ProtocolVectors}: an Android instrumented
 * test runs on a device and can only see what is packaged into the artifact.
 */
public final class ProtocolConformance {

    private static final byte[] VECTOR_SALT = {1, 2, 3, 4};
    private static final byte[] VECTOR_INFO = "tetherless-vectors".getBytes(StandardCharsets.UTF_8);

    private ProtocolConformance() {
    }

    /** Runs every check. An empty list means this platform is conformant. */
    public static List<String> run() {
        List<String> failures = new ArrayList<>();
        checkWireFormat(failures);
        checkHkdf(failures);
        checkDh(failures);
        return failures;
    }

    // ------------------------------------------------------------ wire format

    private static void checkWireFormat(List<String> failures) {
        Map<String, String> expected;
        try {
            expected = ProtocolVectors.expectedHex();
        } catch (Exception e) {
            failures.add("could not read " + ProtocolVectors.RESOURCE + ": " + e.getMessage());
            return;
        }

        for (ProtocolVectors.Vector vector : ProtocolVectors.all()) {
            String want = expected.get(vector.name);
            if (want == null) {
                failures.add("vector '" + vector.name + "' has no committed bytes");
                continue;
            }
            try {
                String got = ProtocolVectors.toHex(MessageCodec.encode(vector.message));
                if (!want.equals(got)) {
                    failures.add("vector '" + vector.name + "' encodes differently on this platform"
                            + "\n  expected " + want + "\n  actual   " + got);
                    continue;
                }
                compareFields(failures, vector, MessageCodec.decode(ProtocolVectors.hex(want)));
            } catch (Exception e) {
                failures.add("vector '" + vector.name + "' threw: " + e);
            }
        }
    }

    private static void compareFields(List<String> failures,
                                      ProtocolVectors.Vector vector, Message decoded) {
        Message want = vector.message;
        String at = "vector '" + vector.name + "' field ";
        if (want.getType() != decoded.getType()) {
            failures.add(at + "type: " + want.getType() + " vs " + decoded.getType());
        }
        if (want.getProtocolVersion() != decoded.getProtocolVersion()) {
            failures.add(at + "protocolVersion");
        }
        if (!eq(want.getMessageId(), decoded.getMessageId())) {
            failures.add(at + "messageId");
        }
        if (!eq(want.getSenderId(), decoded.getSenderId())) {
            failures.add(at + "senderId");
        }
        if (!eq(want.getReceiverId(), decoded.getReceiverId())) {
            failures.add(at + "receiverId: " + want.getReceiverId() + " vs " + decoded.getReceiverId());
        }
        if (want.getTimestamp() != decoded.getTimestamp()) {
            failures.add(at + "timestamp");
        }
        if (!Arrays.equals(want.getPayload(), decoded.getPayload())) {
            failures.add(at + "payload");
        }
        if (!Arrays.equals(want.getIv(), decoded.getIv())) {
            failures.add(at + "iv");
        }
        if (!Arrays.equals(want.getSignature(), decoded.getSignature())) {
            failures.add(at + "signature");
        }
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    // -------------------------------------------------------------- key derivation

    private static void checkHkdf(List<String> failures) {
        try {
            // RFC 5869 A.1, so a bug cannot be baked in as the expected value.
            String okm = ProtocolVectors.toHex(DHUtils.hkdfSha256(
                    ProtocolVectors.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                    ProtocolVectors.hex("000102030405060708090a0b0c"),
                    ProtocolVectors.hex("f0f1f2f3f4f5f6f7f8f9"), 42));
            String want = "3cb25f25faacd57a90434f64d0362f2a"
                    + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
            if (!want.equals(okm)) {
                failures.add("HKDF disagrees with RFC 5869 A.1\n  expected " + want
                        + "\n  actual   " + okm);
            }
        } catch (Exception e) {
            failures.add("HKDF threw: " + e);
        }
    }

    private static void checkDh(List<String> failures) {
        check(failures, "dh-plain", 6, 340,
                "fc7a0542d9a69cdae9783d5e667a9fa0e62df1120ec359c7de61fdd673c08a4c");
        // Raw secret is 255 bytes, not 256. A provider that returns the short array unpadded
        // derives a different key here, which is the intermittent interop bug this guards.
        check(failures, "dh-leading-zero", 1000, 1001,
                "1286daf9226f806b8ff4bdb777d613012173e101046c5d600250a1f8edbda555");
    }

    private static void check(List<String> failures, String name, long own, long peer, String want) {
        try {
            KeyFactory kf = KeyFactory.getInstance("DH");
            BigInteger p = DHUtils.GROUP14_P;
            BigInteger g = DHUtils.GROUP14_G;
            String got = ProtocolVectors.toHex(DHUtils.generateSharedSecret(
                    kf.generatePrivate(new DHPrivateKeySpec(BigInteger.valueOf(own), p, g)),
                    kf.generatePublic(new DHPublicKeySpec(
                            g.modPow(BigInteger.valueOf(peer), p), p, g)),
                    VECTOR_SALT, VECTOR_INFO));
            if (!want.equals(got)) {
                failures.add("DH vector '" + name + "' differs on this platform"
                        + "\n  expected " + want + "\n  actual   " + got);
            }
        } catch (Exception e) {
            failures.add("DH vector '" + name + "' threw: " + e);
        }
    }
}
