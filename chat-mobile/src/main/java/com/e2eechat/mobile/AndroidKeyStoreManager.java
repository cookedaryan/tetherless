package com.e2eechat.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.e2eechat.core.keys.IdentityKeyStore;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.X509EncodedKeySpec;

import java.util.Optional;

/**
 * Identity storage backed by the Android keystore.
 *
 * <p>The private key is generated inside {@code AndroidKeyStore} and never leaves it: signing is
 * delegated to the keystore rather than the key being handed to us. On hardware that supports it
 * the key is held in a secure element, so an attacker with the device still cannot extract it.
 *
 * <p>The consequence, which the UI must be honest about, is that <strong>the identity cannot be
 * exported or backed up</strong>. Uninstalling the app destroys it permanently, and every peer will
 * see a key change when a new one is generated.
 *
 * <p>Peer public keys are ordinary public data and are kept in preferences; nothing is gained by
 * putting them in the keystore.
 */
public class AndroidKeyStoreManager implements IdentityKeyStore {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String IDENTITY_ALIAS = "tetherless-identity";
    private static final String PEER_PREFS = "tetherless-peer-keys";

    private final SharedPreferences peerKeys;

    public AndroidKeyStoreManager(Context context) {
        this.peerKeys = context.getApplicationContext()
                .getSharedPreferences(PEER_PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Returns the device identity, generating it on first use.
     *
     * @param passphrase ignored: the Android keystore guards the key with the platform's own
     *                   protections rather than one we supply. Present only to satisfy the shared
     *                   {@link IdentityKeyStore} contract, which the desktop's passphrase-protected
     *                   PKCS#12 store needs.
     */
    @Override
    public KeyPair loadOrCreateIdentity(char[] passphrase) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(PROVIDER);
        keyStore.load(null);

        if (keyStore.containsAlias(IDENTITY_ALIAS)) {
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(IDENTITY_ALIAS, null);
            Certificate certificate = keyStore.getCertificate(IDENTITY_ALIAS);
            if (privateKey != null && certificate != null) {
                return new KeyPair(certificate.getPublicKey(), privateKey);
            }
            // Half-created entry: start again rather than limp along without a usable identity.
            keyStore.deleteEntry(IDENTITY_ALIAS);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, PROVIDER);
        generator.initialize(new KeyGenParameterSpec.Builder(
                IDENTITY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                // Deliberately not requiring user authentication: the service must be able to sign
                // while the screen is locked, or messages stop flowing in the background.
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKeyPair();
    }

    @Override
    public void storePeerKey(String peerId, PublicKey key) {
        peerKeys.edit()
                .putString(peerId, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP))
                .apply();
    }

    @Override
    public Optional<PublicKey> getPeerKey(String peerId) {
        String encoded = peerKeys.getString(peerId, null);
        if (encoded == null) {
            return Optional.empty();
        }
        try {
            byte[] der = Base64.decode(encoded, Base64.NO_WRAP);
            return Optional.of(java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Safety number for a key. Byte-identical to the desktop implementation, so the two platforms
     * show the same digits for the same peer - otherwise comparing them out of band is useless.
     */
    @Override
    public String fingerprint(PublicKey key) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
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
