package com.e2eechat.core.crypto;

import org.junit.Test;

import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPublicKey;
import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The raw Diffie-Hellman secret must not outlive the key derived from it.
 *
 * <p>It is the input every message key descends from, and it used to be dropped for the garbage
 * collector to overwrite whenever it got round to it - so it sat in the heap, readable to a crash
 * dump or anything able to inspect the process, long after it stopped being needed.
 *
 * <p>Java gives no way to observe another method's local arrays, so this does not try to. It
 * asserts the two properties that are observable and that a regression would break: the derivation
 * still produces the right key, and the same agreement recomputed by hand is not left behind in
 * anything the method returns.
 */
@SuppressWarnings("deprecation")
public class SecretWipingTest {

    /** The derived key is unchanged by the wiping - the point is to zero copies, not the output. */
    @Test
    public void bothSidesStillDeriveTheSameKey() throws Exception {
        KeyPair alice = DHUtils.generateKeyPair();
        KeyPair bob = DHUtils.generateKeyPairFromParams(alice.getPublic());

        byte[] salt = new byte[32];
        byte[] info = "tetherless-v1 aes-256-gcm".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] fromAlice = DHUtils.generateSharedSecret(alice.getPrivate(), bob.getPublic(), salt, info);
        byte[] fromBob = DHUtils.generateSharedSecret(bob.getPrivate(), alice.getPublic(), salt, info);

        assertEquals32(fromAlice);
        assertArrayEquals("the two sides must still agree", fromAlice, fromBob);
    }

    /** Deriving twice from the same keys is stable, so nothing was consumed or corrupted. */
    @Test
    public void derivingTwiceFromTheSameKeysAgrees() throws Exception {
        KeyPair alice = DHUtils.generateKeyPair();
        KeyPair bob = DHUtils.generateKeyPairFromParams(alice.getPublic());
        byte[] salt = new byte[32];
        byte[] info = "info".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] first = DHUtils.generateSharedSecret(alice.getPrivate(), bob.getPublic(), salt, info);
        byte[] second = DHUtils.generateSharedSecret(alice.getPrivate(), bob.getPublic(), salt, info);

        assertArrayEquals("a wipe must not have disturbed the private key or the agreement",
                first, second);
    }

    /**
     * The derived key must not simply be the agreement itself.
     *
     * <p>If it were, wiping the copies would be pointless - the secret would be leaving the method
     * in the return value. This pins that the HKDF stage is really there.
     */
    @Test
    public void theDerivedKeyIsNotTheRawAgreement() throws Exception {
        KeyPair alice = DHUtils.generateKeyPair();
        KeyPair bob = DHUtils.generateKeyPairFromParams(alice.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("DH");
        agreement.init(alice.getPrivate());
        agreement.doPhase(bob.getPublic(), true);
        byte[] raw = agreement.generateSecret();

        byte[] derived = DHUtils.generateSharedSecret(alice.getPrivate(), bob.getPublic(),
                new byte[32], "info".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals32(derived);
        assertFalse("the derived key must not be a prefix of the raw agreement",
                startsWith(raw, derived));
    }

    /** HKDF still matches itself across calls, so zeroing prk and t did not change the output. */
    @Test
    public void hkdfIsStableAcrossCalls() throws Exception {
        byte[] ikm = new byte[64];
        Arrays.fill(ikm, (byte) 7);
        byte[] salt = new byte[32];
        byte[] info = "expand".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        byte[] first = DHUtils.hkdfSha256(ikm, salt, info, 64);
        byte[] second = DHUtils.hkdfSha256(ikm, salt, info, 64);

        assertArrayEquals(first, second);
        assertEquals(64, first.length);
    }

    /** The caller's own input is theirs; the method must not wipe what it was handed. */
    @Test
    public void hkdfDoesNotWipeTheCallersInput() throws Exception {
        byte[] ikm = new byte[64];
        Arrays.fill(ikm, (byte) 9);
        byte[] untouched = ikm.clone();

        DHUtils.hkdfSha256(ikm, new byte[32], new byte[]{1}, 32);

        assertArrayEquals("hkdfSha256 zeroed input it does not own", untouched, ikm);
    }

    private static void assertEquals32(byte[] key) {
        assertNotNull(key);
        assertEquals(32, key.length);
        boolean allZero = true;
        for (byte b : key) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        assertFalse("the derived key is all zeroes; something wiped the output", allZero);
    }

    private static void assertEquals(int expected, int actual) {
        assertTrue("expected " + expected + " but was " + actual, expected == actual);
    }

    private static boolean startsWith(byte[] haystack, byte[] needle) {
        if (haystack.length < needle.length) {
            return false;
        }
        return Arrays.equals(Arrays.copyOf(haystack, needle.length), needle);
    }
}
