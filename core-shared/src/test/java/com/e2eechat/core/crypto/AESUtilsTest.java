package com.e2eechat.core.crypto;

import org.junit.Assert;
import org.junit.Test;
import javax.crypto.SecretKey;

public class AESUtilsTest {
    @Test
    public void testEncryptionDecryption() throws Exception {
        SecretKey key = AESUtils.generateAESKey();
        byte[] iv = AESUtils.generateIV();
        
        String originalText = "Hello, End-to-End Encryption!";
        byte[] cipherText = AESUtils.encrypt(originalText.getBytes(), key, iv);
        byte[] decryptedText = AESUtils.decrypt(cipherText, key, iv);
        
        Assert.assertEquals(originalText, new String(decryptedText));
    }
}
