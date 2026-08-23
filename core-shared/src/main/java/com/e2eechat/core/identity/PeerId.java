package com.e2eechat.core.identity;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Locale;

/**
 * The address a peer is routed to: a pure function of their identity public key.
 *
 * <h2>Why this exists</h2>
 * Peer ids used to be {@code displayName@fingerprintPrefix}. That made a cosmetic field
 * load-bearing for delivery - renaming yourself changed your address and made you unreachable to
 * everyone holding the old one. It also carried only the first 7 hex digits of the fingerprint,
 * about 28 bits, so two users collide after roughly 16,000 identities; because the relay rejects a
 * duplicate id with {@code ID_TAKEN}, a collision locks the second user out of their own account.
 *
 * <p>An id is now {@value #ID_BYTES} bytes ({@value #ID_LENGTH} lowercase hex characters) of
 * SHA-256 over the encoded public key. It is stable for the life of the key, independent of what
 * anyone calls themselves, and wide enough that collisions are not a practical concern. Display
 * names travel separately as metadata; see {@code HelloPayload}.
 *
 * <p>The id is a truncated hash and therefore an <em>address</em>, not proof of identity. Proof
 * comes from signature verification against the stored key, and from comparing safety numbers.
 */
public final class PeerId {

    /** Bytes of the digest kept. 16 bytes is 128 bits, the usual width for an opaque identifier. */
    public static final int ID_BYTES = 16;

    /** Length of the rendered id, two hex characters per byte. */
    public static final int ID_LENGTH = ID_BYTES * 2;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PeerId() {
    }

    /** Derives the routing id for an identity public key. */
    public static String of(PublicKey publicKey) {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            StringBuilder sb = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_BYTES; i++) {
                sb.append(HEX[(digest[i] >> 4) & 0xF]).append(HEX[digest[i] & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is mandated by the JCE spec, so this cannot happen on a sane runtime.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True if {@code id} is well-formed: exactly {@value #ID_LENGTH} lowercase hex characters. */
    public static boolean isValid(String id) {
        if (id == null || id.length() != ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Accepts an id typed or pasted by a user, tolerating case and the grouping separators that
     * {@link #forDisplay(String)} adds.
     *
     * @return the canonical id, or null if the input is not a peer id
     */
    public static String parse(String input) {
        if (input == null) {
            return null;
        }
        String cleaned = input.trim().replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
        return isValid(cleaned) ? cleaned : null;
    }

    /** Groups an id into hyphenated blocks of 8, which is far easier to read aloud or compare. */
    public static String forDisplay(String id) {
        if (!isValid(id)) {
            return id == null ? "" : id;
        }
        StringBuilder sb = new StringBuilder(ID_LENGTH + 3);
        for (int i = 0; i < id.length(); i += 8) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(id, i, Math.min(i + 8, id.length()));
        }
        return sb.toString();
    }

    /**
     * A compact label for a peer with no known display name - the first block of the id. Only ever
     * a fallback for the UI; never use it for routing or comparison.
     */
    public static String shortForm(String id) {
        if (id == null || id.isEmpty()) {
            return "unknown";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /**
     * True for the pre-2 {@code displayName@fingerprintPrefix} form, so callers can recognise
     * conversations stored before the id format changed and label them rather than crash.
     */
    public static boolean isLegacyFormat(String id) {
        return id != null && id.indexOf('@') > 0 && !isValid(id);
    }
}
