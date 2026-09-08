package com.e2eechat.core.keys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
                LOG.warn("Could not read the stored peer keys at {}; starting with none",
                        peersFile.getAbsolutePath(), e);
            }
        }
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
        File parent = ksFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Could not create the configuration directory at " + parent);
        }

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
        LOG.info("Created a new identity key at {}", ksFile.getAbsolutePath());
    }

    @Override
    public synchronized void storePeerKey(String peerId, PublicKey key) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
        peerProperties.setProperty(peerId, b64);
        try (FileOutputStream fos = new FileOutputStream(peersFile)) {
            peerProperties.store(fos, "Tetherless Peer Public Keys");
        }
    }
    
    @Override
    public synchronized Optional<PublicKey> getPeerKey(String peerId) throws Exception {
        String b64 = peerProperties.getProperty(peerId);
        if (b64 == null) {
            return Optional.empty();
        }
        byte[] keyBytes = Base64.getDecoder().decode(b64);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return Optional.of(kf.generatePublic(new X509EncodedKeySpec(keyBytes)));
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
