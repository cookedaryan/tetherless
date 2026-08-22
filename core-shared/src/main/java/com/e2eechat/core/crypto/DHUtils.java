package com.e2eechat.core.crypto;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Note: Finite-field DH was chosen for JCE ubiquity in v1.0. 
 * X25519 (available from Java 11 and Android 31+) is the preferred successor.
 * @deprecated Consider migrating to X25519 (XDH) for future versions (FUTURE-02).
 */
@Deprecated
public class DHUtils {

    // RFC 3526 MODP Group 14 (2048-bit)
    private static final String P_HEX = 
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
        "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
        "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
        "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
        "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
        "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
        "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
        "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
        "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
        "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
        "15728E5A8AACAA68FFFFFFFFFFFFFFFF";
    
    public static final BigInteger GROUP14_P = new BigInteger(P_HEX, 16);
    public static final BigInteger GROUP14_G = BigInteger.valueOf(2);
    public static final DHParameterSpec GROUP14_SPEC = new DHParameterSpec(GROUP14_P, GROUP14_G);

    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
        keyPairGenerator.initialize(GROUP14_SPEC);
        return keyPairGenerator.generateKeyPair();
    }
    
    public static KeyPair generateKeyPairFromParams(PublicKey otherPartyPublicKey) throws Exception {
        DHParameterSpec dhParam = ((DHPublicKey)otherPartyPublicKey).getParams();
        
        // Validate parameters
        if (!dhParam.getP().equals(GROUP14_P) || !dhParam.getG().equals(GROUP14_G)) {
            throw new IllegalArgumentException("Rejected DH parameters: must use RFC 3526 Group 14");
        }
        
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
        keyPairGenerator.initialize(dhParam);
        return keyPairGenerator.generateKeyPair();
    }

    public static byte[] generateSharedSecret(PrivateKey privateKey, PublicKey otherPartyPublicKey, byte[] salt, byte[] info) throws Exception {
        // Validate public key y
        BigInteger y = ((DHPublicKey)otherPartyPublicKey).getY();
        if (y.compareTo(BigInteger.ONE) <= 0 || y.compareTo(GROUP14_P.subtract(BigInteger.ONE)) >= 0) {
            throw new IllegalArgumentException("Invalid DH public key: out of bounds");
        }
        
        // y^q mod p == 1 (For Group 14, q = (p-1)/2)
        BigInteger q = GROUP14_P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2));
        if (!y.modPow(q, GROUP14_P).equals(BigInteger.ONE)) {
            throw new IllegalArgumentException("Invalid DH public key: small subgroup attack detected");
        }
        
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(otherPartyPublicKey, true);
        
        byte[] rawSecret = keyAgreement.generateSecret();
        
        // Left-pad to byte length of P (256 bytes) to fix JVM-Android interop bugs
        byte[] paddedSecret = new byte[256];
        if (rawSecret.length < 256) {
            System.arraycopy(rawSecret, 0, paddedSecret, 256 - rawSecret.length, rawSecret.length);
        } else if (rawSecret.length > 256) {
            // Should not happen for 2048-bit, but just in case
            System.arraycopy(rawSecret, rawSecret.length - 256, paddedSecret, 0, 256);
        } else {
            paddedSecret = rawSecret;
        }
        
        return hkdfSha256(paddedSecret, salt, info, 32);
    }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        
        if (salt == null || salt.length == 0) {
            salt = new byte[mac.getMacLength()];
        }
        
        // Extract
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        
        // Expand
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int generatedBytes = 0;
        int i = 1;
        
        while (generatedBytes < length) {
            mac.update(t);
            mac.update(info);
            mac.update((byte) i);
            t = mac.doFinal();
            
            int toCopy = Math.min(t.length, length - generatedBytes);
            System.arraycopy(t, 0, okm, generatedBytes, toCopy);
            generatedBytes += toCopy;
            i++;
        }
        
        return okm;
    }

    public static PublicKey getPublicKeyFromBytes(byte[] keyBytes) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }
}
