package com.e2eechat.core.crypto;

import org.junit.Assert;
import org.junit.Test;
import java.security.KeyPair;
import java.util.Arrays;

public class DHUtilsTest {
    @Test
    public void testSharedSecretGeneration() throws Exception {
        KeyPair alicePair = DHUtils.generateKeyPair();
        KeyPair bobPair = DHUtils.generateKeyPairFromParams(alicePair.getPublic());
        
        byte[] aliceShared = DHUtils.generateSharedSecret(alicePair.getPrivate(), bobPair.getPublic());
        byte[] bobShared = DHUtils.generateSharedSecret(bobPair.getPrivate(), alicePair.getPublic());
        
        Assert.assertTrue(Arrays.equals(aliceShared, bobShared));
    }
}
