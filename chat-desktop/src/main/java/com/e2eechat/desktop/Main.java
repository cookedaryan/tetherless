package com.e2eechat.desktop;

import com.e2eechat.core.identity.PeerId;
import com.e2eechat.core.keys.JceKeyStoreManager;
import com.e2eechat.core.session.SessionManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Optional;
import java.util.function.Function;

public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String configDirPath = System.getProperty("tetherless.config.dir", 
                new File(System.getProperty("user.home"), ".tetherless").getAbsolutePath());
        if (configDirPath.startsWith("\"") && configDirPath.endsWith("\"")) {
            configDirPath = configDirPath.substring(1, configDirPath.length() - 1);
        }
        File configDir = new File(configDirPath);
        
        if (!configDir.exists()) {
            if (!configDir.mkdirs()) {
                LOG.error("Failed to create config directory: {}", configDir.getAbsolutePath());
                JOptionPane.showMessageDialog(null, "Failed to create config directory: " + configDir.getAbsolutePath());
                System.exit(1);
            }
        }
        
        String dbPath = new File(configDir, "chat.db").getAbsolutePath();
        DatabaseHelper.initializeDatabase(dbPath);
        
        // Resolves host, port and - critically - which certificate the client pins. A packaged
        // build with nothing configured will refuse to connect rather than trust the development
        // certificate; see TlsSupport.
        DesktopConfig config = DesktopConfig.load(configDir, args);
        config.applyTlsProperties();

        final String finalHost = config.host();
        final int finalPort = config.port();
        
        JceKeyStoreManager keyStoreManager = new JceKeyStoreManager(configDir);
        ProfileStore profileStore = new ProfileStore(configDir);
        boolean isFirstRun = !new File(configDir, "identity.p12").exists();

        // The display name is metadata sent in HELLO, not part of the peer id, but it still has to
        // persist so a peer's label does not change every launch. A profile written before it was
        // stored has none, in which case ask for it once.
        String storedName = profileStore.getDisplayName().orElse(null);
        final IdentityDialog.Mode dialogMode = isFirstRun
                ? IdentityDialog.Mode.FIRST_RUN
                : (storedName == null
                        ? IdentityDialog.Mode.UNLOCK_NEEDS_NAME
                        : IdentityDialog.Mode.UNLOCK);

        SwingUtilities.invokeLater(() -> {
            try {
                com.formdev.flatlaf.FlatLightLaf.setup();
            } catch (Exception ignored) {}
            
            KeyPair identity = null;
            String displayName = storedName;
            String fingerprint = null;
            char[] validPassphrase = null;

            int attempts = 0;
            while (identity == null) {
                IdentityDialog dialog = new IdentityDialog(null, dialogMode);
                dialog.setVisible(true);

                char[] passphrase = dialog.getPassphrase();
                if (passphrase == null) {
                    System.exit(0);
                }
                if (dialog.getDisplayName() != null) {
                    displayName = dialog.getDisplayName();
                }

                try {
                    identity = keyStoreManager.loadOrCreateIdentity(passphrase);
                    fingerprint = keyStoreManager.fingerprint(identity.getPublic());
                    validPassphrase = passphrase;
                } catch (Exception e) {
                    attempts++;
                    if (attempts >= 5) {
                        JOptionPane.showMessageDialog(null, "Too many failed attempts. Exiting.");
                        System.exit(1);
                    }
                    try {
                        Thread.sleep(attempts * 1000L);
                    } catch (InterruptedException ignored) {}
                    
                    JOptionPane.showMessageDialog(null, "Failed to load identity: " + e.getMessage());
                }
            }
            
            // Persist only after the passphrase has actually unlocked the identity, so a failed
            // attempt cannot overwrite a good name.
            profileStore.setDisplayName(displayName);

            // The routing id is a pure function of the identity key. The display name is metadata,
            // sent in HELLO, so renaming yourself no longer changes the address peers reach you at.
            String clientId = PeerId.of(identity.getPublic());
            
            // The database key comes from a typed passphrase, so it is stretched with PBKDF2 rather
            // than HKDF: without a work factor an attacker holding the file guesses at hash speed.
            SecretKey dbKey = null;
            try {
                Optional<ProfileStore.KdfParameters> stored = profileStore.getKdfParameters();
                if (stored.isPresent()) {
                    dbKey = DatabaseKeys.derive(validPassphrase,
                            stored.get().salt, stored.get().iterations);
                } else {
                    // Either a new profile, or one whose database is still encrypted under the old
                    // HKDF derivation. Both end up with fresh PBKDF2 parameters; only the second
                    // has rows to re-encrypt.
                    byte[] salt = DatabaseKeys.newSalt();
                    SecretKey newKey = DatabaseKeys.derive(validPassphrase, salt,
                            DatabaseKeys.ITERATIONS);

                    if (!isFirstRun) {
                        SecretKey legacyKey = DatabaseKeys.legacyHkdf(validPassphrase, clientId);
                        int migrated = new MessageRepository(dbPath, legacyKey)
                                .migrateEncryption(legacyKey, newKey);
                        if (migrated < 0) {
                            JOptionPane.showMessageDialog(null,
                                    "Could not re-encrypt the local message database.\n"
                                            + "It has been left untouched. Please report this.",
                                    "Migration failed", JOptionPane.ERROR_MESSAGE);
                            System.exit(1);
                        }
                    }
                    profileStore.setKdfParameters(salt, DatabaseKeys.ITERATIONS);
                    dbKey = newKey;
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Failed to derive the database key. Exiting.");
                System.exit(1);
            } finally {
                // The passphrase is no longer needed once the key exists.
                if (validPassphrase != null) {
                    java.util.Arrays.fill(validPassphrase, '\0');
                }
            }

            MessageRepository messageRepository = new MessageRepository(dbPath, dbKey);
            
            Function<String, PublicKey> peerKeyLookup = senderId -> {
                try {
                    Optional<PublicKey> opt = keyStoreManager.getPeerKey(senderId);
                    return opt.orElse(null);
                } catch (Exception e) {
                    LOG.warn("No stored key for peer {}: {}", senderId, e.toString());
                    return null;
                }
            };
            
            SessionManager sessionManager = new SessionManager(clientId, peerKeyLookup); 
            
            PeerDirectory peerDirectory = new PeerDirectory(configDir);
            ChatClient client = new ChatClient(clientId, identity, sessionManager, messageRepository,
                    keyStoreManager, peerDirectory, displayName);
            
            ChatWindow window = new ChatWindow(client, fingerprint);
            
            window.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    client.disconnect();
                }
            });
            
            window.setVisible(true);
            
            new Thread(() -> {
                client.connect(finalHost, finalPort);
            }, "Connect-Init-Thread").start();
        });
    }
}
