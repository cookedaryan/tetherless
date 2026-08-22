package com.e2eechat.core.keys;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
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
                e.printStackTrace();
            }
        }
    }
    
    @Override
    public KeyPair loadOrCreateIdentity(char[] passphrase) throws Exception {
        File ksFile = new File(configDir, "identity.p12");
        if (!ksFile.exists()) {
            // Generate using keytool via ProcessBuilder
            String pass = new String(passphrase);
            ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "myidentity",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-storetype", "PKCS12",
                "-keystore", ksFile.getAbsolutePath(),
                "-storepass", pass,
                "-keypass", pass,
                "-dname", "CN=TetherlessUser, O=Tetherless, C=US"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                java.util.Scanner s = new java.util.Scanner(p.getInputStream()).useDelimiter("\\A");
                String result = s.hasNext() ? s.next() : "";
                throw new Exception("Keytool failed with code " + exitCode + ": " + result);
            }
        }
        
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(ksFile)) {
            ks.load(fis, passphrase);
        }
        
        PrivateKey privKey = (PrivateKey) ks.getKey("myidentity", passphrase);
        Certificate cert = ks.getCertificate("myidentity");
        PublicKey pubKey = cert.getPublicKey();
        
        return new KeyPair(pubKey, privKey);
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
