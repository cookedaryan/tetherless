package com.e2eechat.core.keys;

import com.e2eechat.core.identity.PeerId;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.util.Optional;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Identity creation and peer key storage.
 *
 * <p>Identity keys are now generated in this process rather than by shelling out to
 * {@code keytool}. The tests that matter most here are the two that would catch a regression in
 * either direction: that a fresh identity is stable across restarts, and that a keystore written
 * by the old {@code keytool} path still opens.
 */
public class JceKeyStoreManagerTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File home;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("profile");
    }

    private static char[] passphrase() {
        return PASSPHRASE.clone();
    }

    @Test
    public void createsAnIdentityOnFirstUse() throws Exception {
        JceKeyStoreManager keyStore = new JceKeyStoreManager(home);

        KeyPair identity = keyStore.loadOrCreateIdentity(passphrase());

        assertNotNull(identity.getPrivate());
        assertNotNull(identity.getPublic());
        assertEquals("RSA", identity.getPublic().getAlgorithm());
        assertTrue("no keystore was written", new File(home, "identity.p12").isFile());
    }

    /** A new identity on every launch would change the user's peer id and orphan their history. */
    @Test
    public void theSameIdentityComesBackOnEveryLaunch() throws Exception {
        KeyPair first = new JceKeyStoreManager(home).loadOrCreateIdentity(passphrase());
        KeyPair second = new JceKeyStoreManager(home).loadOrCreateIdentity(passphrase());

        assertArrayEquals(first.getPublic().getEncoded(), second.getPublic().getEncoded());
        assertEquals(PeerId.of(first.getPublic()), PeerId.of(second.getPublic()));
    }

    @Test
    public void theKeyIsUsableForSigningAndVerifying() throws Exception {
        KeyPair identity = new JceKeyStoreManager(home).loadOrCreateIdentity(passphrase());

        byte[] payload = "a frame to sign".getBytes("UTF-8");
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(identity.getPrivate());
        signer.update(payload);
        byte[] signature = signer.sign();

        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(identity.getPublic());
        verifier.update(payload);
        assertTrue("the stored key pair does not match itself", verifier.verify(signature));
    }

    @Test
    public void theKeystoreIsAnOrdinaryPkcs12ProtectedByThePassphrase() throws Exception {
        new JceKeyStoreManager(home).loadOrCreateIdentity(passphrase());

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(new File(home, "identity.p12"))) {
            store.load(in, passphrase());
        }
        assertTrue(store.containsAlias("myidentity"));
        assertTrue(store.isKeyEntry("myidentity"));
        assertNotNull(store.getCertificate("myidentity"));
    }

    @Test
    public void theWrongPassphraseWillNotOpenIt() throws Exception {
        new JceKeyStoreManager(home).loadOrCreateIdentity(passphrase());

        try {
            new JceKeyStoreManager(home).loadOrCreateIdentity("not the passphrase".toCharArray());
            fail("a keystore opened under the wrong passphrase");
        } catch (Exception expected) {
            // A PKCS#12 store with a bad password fails to load, which is the point.
        }
    }

    @Test
    public void aMissingConfigurationDirectoryIsCreated() throws Exception {
        File nested = new File(home, "does/not/exist/yet");

        KeyPair identity = new JceKeyStoreManager(nested).loadOrCreateIdentity(passphrase());

        assertNotNull(identity.getPublic());
        assertTrue(new File(nested, "identity.p12").isFile());
    }

    /**
     * Identities written by the previous implementation must keep working.
     *
     * <p>That implementation ran {@code keytool}, so this builds a keystore the same way and
     * checks the current code reads it. Skipped where {@code keytool} is not on the path - which,
     * as it happens, is precisely the situation that made the old implementation unusable in a
     * packaged build.
     */
    @Test
    public void aKeystoreWrittenByTheOldKeytoolPathStillOpens() throws Exception {
        File keystore = new File(home, "identity.p12");
        Assume.assumeTrue("keytool is not available on this machine",
                runKeytool(keystore, new String(PASSPHRASE)));

        KeyPair identity = new JceKeyStoreManager(home).loadOrCreateIdentity(passphrase());

        assertNotNull(identity.getPrivate());
        assertEquals("RSA", identity.getPublic().getAlgorithm());
        assertEquals(PeerId.ID_LENGTH, PeerId.of(identity.getPublic()).length());
    }

    /** Generates a keystore exactly as the previous implementation did. Returns false if absent. */
    private static boolean runKeytool(File keystore, String passphrase) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "myidentity",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "3650",
                    "-storetype", "PKCS12",
                    "-keystore", keystore.getAbsolutePath(),
                    "-storepass", passphrase,
                    "-keypass", passphrase,
                    "-dname", "CN=TetherlessUser, O=Tetherless, C=US");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ peers

    @Test
    public void aStoredPeerKeyComesBackUnchanged() throws Exception {
        JceKeyStoreManager keyStore = new JceKeyStoreManager(home);
        PublicKey peer = freshKey();

        keyStore.storePeerKey("peer-one", peer);

        Optional<PublicKey> loaded = keyStore.getPeerKey("peer-one");
        assertTrue(loaded.isPresent());
        assertArrayEquals(peer.getEncoded(), loaded.get().getEncoded());
    }

    @Test
    public void anUnknownPeerIsAbsentRatherThanNull() throws Exception {
        assertFalse(new JceKeyStoreManager(home).getPeerKey("never-seen").isPresent());
    }

    @Test
    public void peerKeysSurviveARestart() throws Exception {
        PublicKey peer = freshKey();
        new JceKeyStoreManager(home).storePeerKey("peer-one", peer);

        Optional<PublicKey> loaded = new JceKeyStoreManager(home).getPeerKey("peer-one");

        assertTrue(loaded.isPresent());
        assertArrayEquals(peer.getEncoded(), loaded.get().getEncoded());
    }

    /**
     * A peer store that will not parse must stop the client, not empty it.
     *
     * <p>This used to log a warning and carry on with no pinned keys at all, which does not read
     * as a security event but is one. Every contact becomes a stranger, so the next HELLO from
     * someone known for months is trusted on sight, and the key-change warning - the one thing
     * standing between a user and an impostor - cannot fire because there is nothing left to
     * compare against. An attacker who can corrupt a file gets a trust reset for free.
     */
    @Test
    public void aTruncatedPeerStoreStopsStartupRatherThanEmptyingIt() throws Exception {
        File dir = tmp.newFolder("truncated");
        JceKeyStoreManager manager = new JceKeyStoreManager(dir);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey peer = generator.generateKeyPair().getPublic();
        manager.storePeerKey(PeerId.of(peer), peer);

        // What an interrupted write actually leaves: the entry is there, the key is half of one.
        // Properties.load reads ISO-8859-1, where every byte sequence is legal text, so this
        // parses perfectly happily - which is exactly why catching the parse exception alone was
        // not enough to notice.
        File store = new File(dir, "peers.properties");
        byte[] whole = java.nio.file.Files.readAllBytes(store.toPath());
        java.nio.file.Files.write(store.toPath(),
                java.util.Arrays.copyOf(whole, whole.length - 40));

        try {
            new JceKeyStoreManager(dir);
            fail("a damaged peer store must not be silently discarded");
        } catch (IllegalStateException expected) {
            assertTrue("the failure should say what to do about it: " + expected.getMessage(),
                    expected.getMessage().contains("peers.properties"));
        }
    }

    /** The other shape of damage: bytes that Properties itself refuses. */
    @Test
    public void aPeerStoreThatWillNotParseStopsStartup() throws Exception {
        File dir = tmp.newFolder("unparseable");
        java.nio.file.Files.write(new File(dir, "peers.properties").toPath(),
                // Assembled rather than written out: javac processes a unicode escape before it
                // lexes the string, so a literal bad one will not compile.
                ("somepeer=" + (char) 92 + "uZZZZ" + (char) 10)
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        try {
            new JceKeyStoreManager(dir);
            fail("an unreadable peer store must not be silently discarded");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("peers.properties"));
        }
    }

    /** A store holding real keys must of course still open. */
    @Test
    public void anIntactPeerStoreOpensNormally() throws Exception {
        File dir = tmp.newFolder("intact");
        JceKeyStoreManager manager = new JceKeyStoreManager(dir);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey first = generator.generateKeyPair().getPublic();
        PublicKey second = generator.generateKeyPair().getPublic();
        manager.storePeerKey(PeerId.of(first), first);
        manager.storePeerKey(PeerId.of(second), second);

        JceKeyStoreManager reopened = new JceKeyStoreManager(dir);
        assertTrue(reopened.getPeerKey(PeerId.of(first)).isPresent());
        assertTrue(reopened.getPeerKey(PeerId.of(second)).isPresent());
    }

    /**
     * The store is replaced by a move, so a reader never sees it half-written.
     *
     * <p>A crash partway through overwriting it left a truncated file, and a truncated file is
     * exactly what the constructor above now refuses to start on - so the atomic write is what
     * keeps that refusal from being something users actually hit.
     */
    @Test
    public void writingAPeerKeyLeavesNoTemporaryFileBehind() throws Exception {
        File dir = tmp.newFolder("atomic");
        JceKeyStoreManager manager = new JceKeyStoreManager(dir);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey peer = generator.generateKeyPair().getPublic();
        manager.storePeerKey(PeerId.of(peer), peer);

        assertTrue("the store should exist", new File(dir, "peers.properties").isFile());
        assertFalse("a temporary file was left next to the store",
                new File(dir, "peers.properties.tmp").exists());

        // And it must be readable by a fresh manager, which is now a strict parse.
        assertTrue(new JceKeyStoreManager(dir).getPeerKey(PeerId.of(peer)).isPresent());
    }

    /**
     * The identity keystore and the peer store must not be readable by other local accounts.
     *
     * <p>They were written with the process umask, which on a typical account leaves them
     * world-readable: any other user on the machine could take the encrypted keystore away and
     * attack the passphrase offline, and read the peer store to learn the whole contact list.
     * Neither file is covered by the end-to-end encryption; both sit outside it, on disk.
     */
    @Test
    public void keyMaterialIsNotReadableByOtherLocalAccounts() throws Exception {
        org.junit.Assume.assumeTrue("POSIX permissions unsupported here",
                java.nio.file.FileSystems.getDefault()
                        .supportedFileAttributeViews().contains("posix"));

        File dir = tmp.newFolder("permissions");
        JceKeyStoreManager manager = new JceKeyStoreManager(dir);
        manager.loadOrCreateIdentity(PASSPHRASE);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey peer = generator.generateKeyPair().getPublic();
        manager.storePeerKey(PeerId.of(peer), peer);

        assertOwnerOnly(new File(dir, "identity.p12"));
        assertOwnerOnly(new File(dir, "peers.properties"));
    }

    private static void assertOwnerOnly(File file) throws Exception {
        assertTrue(file + " should exist", file.isFile());
        java.util.Set<java.nio.file.attribute.PosixFilePermission> mode =
                java.nio.file.Files.getPosixFilePermissions(file.toPath());
        assertFalse(file.getName() + " is group readable",
                mode.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ));
        assertFalse(file.getName() + " is world readable",
                mode.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
    }

    @Test
    public void aFingerprintIsStableAndKeySpecific() throws Exception {
        JceKeyStoreManager keyStore = new JceKeyStoreManager(home);
        PublicKey one = freshKey();
        PublicKey two = freshKey();

        assertEquals(keyStore.fingerprint(one), keyStore.fingerprint(one));
        assertFalse(keyStore.fingerprint(one).equals(keyStore.fingerprint(two)));
    }

    private static PublicKey freshKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPublic();
    }
}
