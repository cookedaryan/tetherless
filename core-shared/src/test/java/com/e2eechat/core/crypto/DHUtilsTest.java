package com.e2eechat.core.crypto;

import org.junit.Test;
import java.security.KeyPair;
import static org.junit.Assert.*;

public class DHUtilsTest {

    @Test
    public void testDHKeyExchange() throws Exception {
        long start = System.currentTimeMillis();
        KeyPair alicePair = DHUtils.generateKeyPair();
        KeyPair bobPair = DHUtils.generateKeyPairFromParams(alicePair.getPublic());
        long end = System.currentTimeMillis();
        
        System.out.println("Key generation took: " + (end - start) + "ms");
        assertTrue("Key generation should be fast (< 500ms)", (end - start) < 500);
        
        byte[] salt = new byte[32];
        byte[] info = "tetherless-v1 aes-256-gcm".getBytes();

        byte[] aliceShared = DHUtils.generateSharedSecret(alicePair.getPrivate(), bobPair.getPublic(), salt, info);
        byte[] bobShared = DHUtils.generateSharedSecret(bobPair.getPrivate(), alicePair.getPublic(), salt, info);
        
        assertArrayEquals(aliceShared, bobShared);
        assertEquals(32, aliceShared.length);
    }
}
