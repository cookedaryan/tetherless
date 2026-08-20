package com.e2eechat.core.crypto;

import javax.crypto.KeyAgreement;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.AlgorithmParameterGenerator;
import java.security.AlgorithmParameters;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;

public class DHUtils {
    public static KeyPair generateKeyPair() throws Exception {
        AlgorithmParameterGenerator paramGen = AlgorithmParameterGenerator.getInstance("DH");
        paramGen.init(2048);
        AlgorithmParameters params = paramGen.generateParameters();
        DHParameterSpec dhSpec = params.getParameterSpec(DHParameterSpec.class);
        
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
        keyPairGenerator.initialize(dhSpec);
        return keyPairGenerator.generateKeyPair();
    }
    
    public static KeyPair generateKeyPairFromParams(PublicKey otherPartyPublicKey) throws Exception {
        DHParameterSpec dhParamFromOtherPartyPubKey = ((DHPublicKey)otherPartyPublicKey).getParams();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
        keyPairGenerator.initialize(dhParamFromOtherPartyPubKey);
        return keyPairGenerator.generateKeyPair();
    }

    public static byte[] generateSharedSecret(PrivateKey privateKey, PublicKey otherPartyPublicKey) throws Exception {
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(otherPartyPublicKey, true);
        
        byte[] sharedSecret = keyAgreement.generateSecret();
        
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(sharedSecret);
        byte[] derivedKey = hash.digest();
        
        return derivedKey;
    }

    public static PublicKey getPublicKeyFromBytes(byte[] keyBytes) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        return keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));
    }
}
