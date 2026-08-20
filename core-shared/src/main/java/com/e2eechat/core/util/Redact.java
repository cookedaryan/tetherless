package com.e2eechat.core.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Redact {
    /**
     * Redacts an ID by returning the first 6 characters of its SHA-256 hash.
     * Thread-safe.
     * @param id The input ID to redact, nullable.
     * @return The redacted ID string, or "null" if the input is null.
     */
    public static String id(String id) {
        if (id == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 3; i++) { // First 3 bytes = 6 hex chars
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }
}
