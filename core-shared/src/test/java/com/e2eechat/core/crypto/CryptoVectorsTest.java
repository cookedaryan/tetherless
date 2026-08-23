package com.e2eechat.core.crypto;

import com.e2eechat.core.protocol.ProtocolVectors;
import org.junit.Test;

import javax.crypto.spec.DHPrivateKeySpec;
import javax.crypto.spec.DHPublicKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Fixed-input, fixed-output vectors for key derivation.
 *
 * <p>These exist for R1 in the development plan: the JVM and Android disagreeing about crypto. The
 * mitigations in {@link DHUtils} - a fixed RFC 3526 group, public-key validation and left-padding
 * the shared secret - were written but never verified on Android. Pinning the outputs means a
 * provider difference fails here, loudly and reproducibly, instead of surfacing as a handshake that
 * works most of the time.
 *
 * <p>{@code ProtocolConformance} in the Android instrumented suite asserts the same values.
 */
public class CryptoVectorsTest {

    private static final byte[] VECTOR_SALT = {1, 2, 3, 4};
    private static final byte[] VECTOR_INFO = "tetherless-vectors".getBytes(StandardCharsets.UTF_8);

    // ------------------------------------------------------------------ HKDF

    /**
     * RFC 5869 Appendix A.1 - the published SHA-256 vector. Checking against the standard rather
     * than against our own output means a bug in the implementation cannot be baked in as expected.
     */
    @Test
    public void hkdfMatchesRfc5869TestCase1() throws Exception {
        byte[] ikm = ProtocolVectors.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = ProtocolVectors.hex("000102030405060708090a0b0c");
        byte[] info = ProtocolVectors.hex("f0f1f2f3f4f5f6f7f8f9");

        byte[] okm = DHUtils.hkdfSha256(ikm, salt, info, 42);

        assertEquals("3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865",
                ProtocolVectors.toHex(okm));
    }

    /** RFC 5869 Appendix A.3 - empty salt and info, which exercises the default-salt path. */
    @Test
    public void hkdfMatchesRfc5869TestCase3() throws Exception {
        byte[] ikm = ProtocolVectors.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");

        byte[] okm = DHUtils.hkdfSha256(ikm, new byte[0], new byte[0], 42);

        assertEquals("8da4e775a563c18f715f802a063c5a31"
                        + "b8a11f5c5ee1879ec3454e5f3c738d2d"
                        + "9d201395faa4b61a96c8",
                ProtocolVectors.toHex(okm));
    }

    @Test
    public void hkdfIsDeterministic() throws Exception {
        byte[] ikm = "input keying material".getBytes(StandardCharsets.UTF_8);
        assertEquals(ProtocolVectors.toHex(DHUtils.hkdfSha256(ikm, VECTOR_SALT, VECTOR_INFO, 32)),
                ProtocolVectors.toHex(DHUtils.hkdfSha256(ikm, VECTOR_SALT, VECTOR_INFO, 32)));
    }

    // -------------------------------------------------------------------- DH

    /** A shared secret that fills the modulus: the ordinary case. */
    @Test
    public void dhDerivationMatchesVector() throws Exception {
        assertEquals("fc7a0542d9a69cdae9783d5e667a9fa0e62df1120ec359c7de61fdd673c08a4c",
                ProtocolVectors.toHex(derive(6, 340)));
    }

    /**
     * The case that makes providers disagree.
     *
     * <p>Here the raw shared secret is 255 bytes rather than 256, because its leading byte is zero.
     * Some providers hand back the short array and some pad it, so a client that does not normalise
     * derives a different key from its peer - and since a random secret is short about one time in
     * 256, the resulting handshake failure is intermittent and close to impossible to reproduce by
     * hand. {@link DHUtils} left-pads to the modulus length; this vector pins that behaviour.
     */
    @Test
    public void dhDerivationMatchesVectorWhenSecretHasLeadingZero() throws Exception {
        assertEquals("1286daf9226f806b8ff4bdb777d613012173e101046c5d600250a1f8edbda555",
                ProtocolVectors.toHex(derive(1000, 1001)));
    }

    /** Guards the premise of the test above: this pair really does produce a short secret. */
    @Test
    public void theLeadingZeroVectorReallyIsShort() {
        BigInteger p = DHUtils.GROUP14_P;
        BigInteger secret = DHUtils.GROUP14_G
                .modPow(BigInteger.valueOf(1001), p)
                .modPow(BigInteger.valueOf(1000), p);
        int rawLength = (secret.bitLength() + 7) / 8;
        assertEquals("vector no longer exercises the leading-zero path", 255, rawLength);
    }

    /** Both sides of a real exchange must land on the same key. */
    @Test
    public void bothPartiesDeriveTheSameKey() throws Exception {
        assertEquals(ProtocolVectors.toHex(derive(6, 340)),
                ProtocolVectors.toHex(derive(340, 6)));
    }

    /** Changing only the salt must change the key: the handshake nonces have to matter. */
    @Test
    public void saltIsMixedIn() throws Exception {
        KeyFactory kf = KeyFactory.getInstance("DH");
        PrivateKey priv = privateKey(kf, 6);
        PublicKey pub = publicKey(kf, 340);

        String withSalt = ProtocolVectors.toHex(
                DHUtils.generateSharedSecret(priv, pub, VECTOR_SALT, VECTOR_INFO));
        String otherSalt = ProtocolVectors.toHex(
                DHUtils.generateSharedSecret(priv, pub, new byte[]{9, 9, 9, 9}, VECTOR_INFO));

        assertTrue("salt must affect the derived key", !withSalt.equals(otherSalt));
    }

    @Test
    public void derivedKeyIs32Bytes() throws Exception {
        assertEquals(32, derive(6, 340).length);
    }

    // ----------------------------------------------------------------- setup

    /**
     * Derives with fixed exponents. Keys are built from raw exponents rather than generated, so the
     * inputs are identical on every platform and every run.
     */
    private static byte[] derive(long ownExponent, long peerExponent) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("DH");
        return DHUtils.generateSharedSecret(
                privateKey(kf, ownExponent), publicKey(kf, peerExponent),
                VECTOR_SALT, VECTOR_INFO);
    }

    private static PrivateKey privateKey(KeyFactory kf, long exponent) throws Exception {
        return kf.generatePrivate(new DHPrivateKeySpec(
                BigInteger.valueOf(exponent), DHUtils.GROUP14_P, DHUtils.GROUP14_G));
    }

    private static PublicKey publicKey(KeyFactory kf, long exponent) throws Exception {
        BigInteger y = DHUtils.GROUP14_G.modPow(BigInteger.valueOf(exponent), DHUtils.GROUP14_P);
        return kf.generatePublic(new DHPublicKeySpec(y, DHUtils.GROUP14_P, DHUtils.GROUP14_G));
    }
}
