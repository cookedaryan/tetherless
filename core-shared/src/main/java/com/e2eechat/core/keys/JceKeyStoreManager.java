package com.e2eechat.core.keys;

import com.e2eechat.core.util.PrivateFiles;
import com.e2eechat.core.util.Redact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

public class JceKeyStoreManager implements IdentityKeyStore {

    private static final Logger LOG = LoggerFactory.getLogger(JceKeyStoreManager.class);

    /** Keystore holding this device's long-term identity key. */
    private static final String KEYSTORE_FILE = "identity.p12";

    /** Entry alias. Unchanged from the keytool-generated format, so old keystores still open. */
    private static final String ALIAS = "myidentity";

    /** Subject of the container certificate. Carries no meaning; identity is the key's hash. */
    private static final String CERTIFICATE_NAME = "Tetherless Identity";

    private static final int IDENTITY_KEY_BITS = 2048;
    
    private final File configDir;
    private final File peersFile;
    private final Properties peerProperties;
    
    public JceKeyStoreManager(File configDir) {
        this.configDir = configDir;
        this.peersFile = new File(configDir, "peers.properties");
        this.peerProperties = new Properties();
        
        if (peersFile.exists()) {
            try (FileInputStream fis = new FileInputStream(peersFile)) {
                peerProperties.load(fis);
            } catch (Exception e) {
                // Fails closed, and the difference matters. This used to log and carry on with an
                // empty store, which does not read as a security event but is one: every pinned
                // key is gone, so the next HELLO from a contact we have known for months is
                // treated as a first meeting and trusted on sight. The warning that a key had
                // changed - the single thing standing between a user and an impostor - cannot
                // fire, because there is nothing left to compare against. Refusing to start is
                // recoverable; silently re-trusting everyone is not.
                throw unusableStore("it could not be read", e);
            }
            verifyEveryPinnedKeyParses();
        }
    }

    /**
     * Refuses to run on a peer store that is present but damaged.
     *
     * <p>{@link Properties#load} is not the check it looks like. It reads ISO-8859-1, where every
     * byte sequence is legal text, so a truncated or overwritten file usually parses without
     * complaint and yields entries that are simply wrong - a half-written Base64 value, or a peer
     * silently missing. Catching the exception alone would have left the common case of corruption
     * sailing straight through, so every stored value is decoded here instead: if it is not a key
     * we could actually verify a signature against, the store is not trustworthy.
     *
     * <p>Property names are deliberately not validated. Ids pinned before the key-derived format
     * are still legitimate entries, and refusing to start on one would turn an upgrade into a
     * lockout.
     */
    private void verifyEveryPinnedKeyParses() {
        for (String peerId : peerProperties.stringPropertyNames()) {
            try {
                decodePeerKey(peerProperties.getProperty(peerId));
            } catch (Exception e) {
                throw unusableStore("the entry for " + Redact.id(peerId)
                        + " is not a usable identity key", e);
            }
        }
    }

    /**
     * The refusal itself.
     *
     * <p>Fails closed, and the difference matters. This used to log and carry on with an empty
     * store, which does not read as a security event but is one: every pinned key is gone, so the
     * next {@code HELLO} from a contact known for months is treated as a first meeting and trusted
     * on sight. The warning that a key had changed - the single thing standing between a user and
     * an impostor - cannot fire, because there is nothing left to compare against. Refusing to
     * start is recoverable; silently re-trusting everyone is not.
     */
    private IllegalStateException unusableStore(String because, Exception cause) {
        return new IllegalStateException(
                "The stored peer keys at " + peersFile.getAbsolutePath() + " are unusable: "
                        + because + ". Refusing to start rather than discard them, because "
                        + "without them a peer presenting a new identity key cannot be told from "
                        + "one you have never met. Restore the file from a backup, or delete it "
                        + "to start over and re-verify every contact's safety number.", cause);
    }
    
    @Override
    public KeyPair loadOrCreateIdentity(char[] passphrase) throws Exception {
        File ksFile = new File(configDir, KEYSTORE_FILE);
        if (!ksFile.exists()) {
            createIdentity(ksFile, passphrase);
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, passphrase);
        }

        PrivateKey privKey = (PrivateKey) ks.getKey(ALIAS, passphrase);
        Certificate cert = ks.getCertificate(ALIAS);
        PublicKey pubKey = cert.getPublicKey();

        return new KeyPair(pubKey, privKey);
    }

    /**
     * Generates a fresh identity key and writes the keystore holding it.
     *
     * <p>Done in this process. It used to be done by running {@code keytool}, which was wrong
     * twice over: the passphrase was passed as a command-line argument, where any process running
     * as the same user can read it out of the process list, and {@code keytool} is a JDK tool the
     * packaged client does not ship - the app image contains one executable, and it is the client.
     * A user who installed a build rather than running from a checkout would have had their first
     * launch fail with nothing on screen to explain it.
     *
     * <p>The keystore format is unchanged, so an identity created by the old path still opens.
     */
    private void createIdentity(File ksFile, char[] passphrase) throws Exception {
        // Owner-only, and the directory before the file: this holds the private identity key, and
        // a default umask on a shared machine makes it world-readable - which hands every other
        // local account an encrypted keystore to attack the passphrase on at leisure.
        PrivateFiles.createPrivateDirectory(ksFile.getParentFile());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(IDENTITY_KEY_BITS);
        KeyPair identity = generator.generateKeyPair();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, passphrase);
        ks.setKeyEntry(ALIAS, identity.getPrivate(), passphrase,
                new Certificate[]{SelfSignedCertificate.generate(identity, CERTIFICATE_NAME)});

        try (FileOutputStream out = new FileOutputStream(ksFile)) {
            ks.store(out, passphrase);
        }
        PrivateFiles.restrict(ksFile);
        LOG.info("Created a new identity key at {}", ksFile.getAbsolutePath());
    }

    /**
     * Pins a peer's identity key.
     *
     * <p>Written to a temporary file and moved into place, so the store on disk is either the old
     * set of keys or the new one and never a half-written file. Overwriting in place meant a
     * crash or a power cut mid-write left a truncated file, and a truncated file is one the next
     * startup cannot parse - which is the door into the trust reset the constructor now refuses
     * to walk through.
     */
    @Override
    public synchronized void storePeerKey(String peerId, PublicKey key) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
        peerProperties.setProperty(peerId, b64);

        File temporary = new File(peersFile.getParentFile(), peersFile.getName() + ".tmp");
        // Created private before anything is written to it, so the contents are never briefly
        // readable; a move preserves the mode, so the live file inherits it.
        java.nio.file.Files.deleteIfExists(temporary.toPath());
        java.nio.file.Files.createFile(temporary.toPath(),
                PrivateFiles.ownerOnlyFileAttributes());
        PrivateFiles.restrict(temporary);
        try (FileOutputStream fos = new FileOutputStream(temporary)) {
            peerProperties.store(fos, "Tetherless Peer Public Keys");
            fos.flush();
            // The move is only atomic with respect to what has actually reached the disk. Without
            // this, a crash can leave the rename done and the contents still in the page cache.
            fos.getFD().sync();
        }

        try {
            Files.move(temporary.toPath(), peersFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            PrivateFiles.restrict(peersFile);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems cannot promise it. A replacing move is still better than writing
            // over the live file, which is the failure this is here to avoid.
            LOG.debug("Atomic move unavailable for {}; falling back to a replacing move",
                    peersFile.getAbsolutePath());
            Files.move(temporary.toPath(), peersFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            PrivateFiles.restrict(peersFile);
        }
    }
    
    @Override
    public synchronized Optional<PublicKey> getPeerKey(String peerId) throws Exception {
        String b64 = peerProperties.getProperty(peerId);
        if (b64 == null) {
            return Optional.empty();
        }
        return Optional.of(decodePeerKey(b64));
    }

    /** Turns one stored value back into a key. Throws if it is not one. */
    private static PublicKey decodePeerKey(String base64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }
    
    @Override
    public String fingerprint(PublicKey key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(key.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            sb.append(String.format("%02X", hash[i]));
            if (i < hash.length - 1 && i % 2 != 0) {
                sb.append(":");
            }
        }
        return sb.toString();
    }
}
