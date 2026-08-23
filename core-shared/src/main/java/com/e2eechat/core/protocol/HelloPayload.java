package com.e2eechat.core.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/**
 * Body of a {@code HELLO}: the sender's identity public key plus the name they wish to be shown as.
 *
 * <p>The display name lives here rather than inside the peer id. An id is derived from the key
 * alone (see {@code PeerId}), so a rename is now just a new {@code HELLO} carrying different
 * metadata - it no longer changes the address people route to.
 *
 * <p><strong>The name is not authenticated by anything but the sender's own signature.</strong> It
 * is a self-asserted label: a peer may call themselves whatever they like, including another
 * person's name. Only the key, and the safety number derived from it, establish who you are talking
 * to. The UI must therefore never present a display name as proof of identity.
 *
 * <p>Wire format, matching the length-prefixed style of {@code MessageCodec}:
 * <pre>
 *   int  keyLength
 *   byte[keyLength]  X.509 encoded public key
 *   int  nameLength
 *   byte[nameLength] UTF-8 display name
 * </pre>
 */
public final class HelloPayload {

    /** Generous ceiling for an X.509 RSA-2048 key; rejects absurd lengths before allocating. */
    private static final int MAX_KEY_BYTES = 8192;

    /** Display names are a label, not a document. */
    public static final int MAX_NAME_BYTES = 128;

    private final PublicKey publicKey;
    private final String displayName;

    private HelloPayload(PublicKey publicKey, String displayName) {
        this.publicKey = publicKey;
        this.displayName = displayName;
    }

    public PublicKey getPublicKey() {
        return publicKey;
    }

    /** The sender's self-asserted name. Never empty; falls back to "" only if none was sent. */
    public String getDisplayName() {
        return displayName;
    }

    public static byte[] encode(PublicKey publicKey, String displayName) throws ProtocolException {
        if (publicKey == null) {
            throw new ProtocolException("HELLO requires a public key");
        }
        byte[] keyBytes = publicKey.getEncoded();
        byte[] nameBytes = truncateUtf8(displayName == null ? "" : displayName.trim());

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(keyBytes.length + nameBytes.length + 8);
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(keyBytes.length);
            dos.write(keyBytes);
            dos.writeInt(nameBytes.length);
            dos.write(nameBytes);
            dos.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ProtocolException("Could not encode HELLO payload: " + e.getMessage());
        }
    }

    /**
     * Decodes a HELLO body.
     *
     * <p>Also accepts a bare X.509 key with no length prefix, which is what protocol version 1
     * sent. Such a peer simply has no display name, and the UI falls back to their short id.
     */
    public static HelloPayload decode(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length == 0) {
            throw new ProtocolException("Empty HELLO payload");
        }

        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(payload));
            int keyLength = dis.readInt();
            if (keyLength <= 0 || keyLength > MAX_KEY_BYTES || keyLength > payload.length - 4) {
                // Not a v2 frame; try reading the whole body as a v1 bare key.
                return new HelloPayload(decodeKey(payload), "");
            }
            byte[] keyBytes = new byte[keyLength];
            dis.readFully(keyBytes);

            String name = "";
            if (dis.available() >= 4) {
                int nameLength = dis.readInt();
                if (nameLength < 0 || nameLength > MAX_NAME_BYTES || nameLength > dis.available()) {
                    throw new ProtocolException("HELLO display name length out of range: " + nameLength);
                }
                byte[] nameBytes = new byte[nameLength];
                dis.readFully(nameBytes);
                name = sanitize(new String(nameBytes, StandardCharsets.UTF_8));
            }
            return new HelloPayload(decodeKey(keyBytes), name);
        } catch (ProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new ProtocolException("Malformed HELLO payload: " + e.getMessage());
        }
    }

    private static PublicKey decodeKey(byte[] keyBytes) throws ProtocolException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new ProtocolException("HELLO did not contain a usable RSA public key");
        }
    }

    /**
     * Strips control characters and collapses whitespace. A name is rendered directly in the chat
     * list and window header, so newlines or bidi overrides in it would let a peer forge the look
     * of the interface.
     */
    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // U+202A..U+202E embedding/override, U+2066..U+2069 isolates: these can reorder
            // surrounding text on screen, letting a name impersonate other interface content.
            boolean bidiControl = c >= 0x202A && c <= 0x202E;
            boolean isolate = c >= 0x2066 && c <= 0x2069;
            if (Character.isISOControl(c) || bidiControl || isolate) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim().replaceAll("\\s{2,}", " ");
    }

    /** Truncates on a character boundary so the result is always valid UTF-8. */
    private static byte[] truncateUtf8(String name) {
        byte[] bytes = sanitize(name).getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_NAME_BYTES) {
            return bytes;
        }
        String s = name;
        while (s.length() > 0) {
            s = s.substring(0, s.length() - 1);
            byte[] candidate = s.getBytes(StandardCharsets.UTF_8);
            if (candidate.length <= MAX_NAME_BYTES) {
                return candidate;
            }
        }
        return new byte[0];
    }
}
