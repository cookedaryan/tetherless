package com.e2eechat.core.keys;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.*;
import java.security.cert.Certificate;
import java.security.spec.KeySpec;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class JceKeyStoreManager implements IdentityKeyStore {
    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String IDENTITY_ALIAS = "identity";
    private static final int ITERATIONS = 210000;
    
    private final File storeFile;
    private final File saltFile;
    
    public JceKeyStoreManager(File configDir) {
        if (!configDir.exists() && !configDir.mkdirs()) {
            throw new RuntimeException("Could not create config directory");
        }
        this.storeFile = new File(configDir, "identity.p12");
        this.saltFile = new File(configDir, "salt.bin");
        restrictPermissions(configDir.toPath());
    }
    
    @Override
    public KeyPair loadOrCreateIdentity(char[] passphrase) throws Exception {
        byte[] salt = loadOrGenerateSalt();
        char[] storePassword = derivePassword(passphrase, salt);
        String storePassStr = new String(storePassword);
        
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        
        if (storeFile.exists()) {
            try (FileInputStream fis = new FileInputStream(storeFile)) {
                ks.load(fis, storePassword);
            } catch (Exception e) {
                throw new Exception("Invalid passphrase or corrupted keystore", e);
            }
            if (ks.containsAlias(IDENTITY_ALIAS)) {
                Key key = ks.getKey(IDENTITY_ALIAS, storePassword);
                Certificate cert = ks.getCertificate(IDENTITY_ALIAS);
                if (key instanceof PrivateKey && cert != null) {
                    return new KeyPair(cert.getPublicKey(), (PrivateKey) key);
                }
            }
        }
        
        // Use keytool to generate the keystore since Java 17 doesn't easily expose self-signed cert generation
        // ProcessBuilder is safe here since we control the arguments.
        if (storeFile.exists()) {
            storeFile.delete();
        }
        
        ProcessBuilder pb = new ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", IDENTITY_ALIAS,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-storetype", KEYSTORE_TYPE,
            "-keystore", storeFile.getAbsolutePath(),
            "-storepass", storePassStr,
            "-keypass", storePassStr,
            "-dname", "CN=TetherlessUser"
        );
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new Exception("keytool failed to generate identity with exit code " + exitCode);
        }
        
        restrictPermissions(storeFile.toPath());
        
        // Load the newly created keystore
        try (FileInputStream fis = new FileInputStream(storeFile)) {
            ks.load(fis, storePassword);
        }
        Key key = ks.getKey(IDENTITY_ALIAS, storePassword);
        Certificate cert = ks.getCertificate(IDENTITY_ALIAS);
        return new KeyPair(cert.getPublicKey(), (PrivateKey) key);
    }
    
    @Override
    public void storePeerKey(String peerId, PublicKey key) throws Exception {
        // Not required to persist in v1.0, could implement via Trusted Certificate Entry if needed.
    }
    
    @Override
    public Optional<PublicKey> getPeerKey(String peerId) throws Exception {
        return Optional.empty();
    }
    
    @Override
    public String fingerprint(PublicKey key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(key.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            sb.append(String.format("%02X", hash[i]));
            if (i < hash.length - 1 && (i + 1) % 2 == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }
    
    private byte[] loadOrGenerateSalt() throws IOException {
        if (saltFile.exists()) {
            byte[] salt = new byte[16];
            try (FileInputStream fis = new FileInputStream(saltFile)) {
                int read = fis.read(salt);
                if (read == 16) return salt;
            }
        }
        byte[] newSalt = new byte[16];
        new SecureRandom().nextBytes(newSalt);
        try (FileOutputStream fos = new FileOutputStream(saltFile)) {
            fos.write(newSalt);
        }
        restrictPermissions(saltFile.toPath());
        return newSalt;
    }
    
    private char[] derivePassword(char[] passphrase, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, 256);
        byte[] keyBytes = f.generateSecret(spec).getEncoded();
        return Base64.getEncoder().encodeToString(keyBytes).toCharArray();
    }

    private void restrictPermissions(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
            } else if (path.getFileSystem().supportedFileAttributeViews().contains("acl")) {
                AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
                if (view != null) {
                    UserPrincipal owner = Files.getOwner(path);
                    AclEntry entry = AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE)
                            .build();
                    view.setAcl(Collections.singletonList(entry));
                }
            }
        } catch (Exception e) {
            // Log and ignore
        }
    }
}
