package com.e2eechat.core.protocol;

import com.e2eechat.core.crypto.RSAUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HelloPayloadTest {

    private static KeyPair keys;

    @BeforeClass
    public static void generateKeys() throws Exception {
        keys = RSAUtils.generateKeyPair();
    }

    @Test
    public void keyAndNameRoundTrip() throws Exception {
        byte[] encoded = HelloPayload.encode(keys.getPublic(), "Aryan");
        HelloPayload decoded = HelloPayload.decode(encoded);

        assertEquals(keys.getPublic(), decoded.getPublicKey());
        assertEquals("Aryan", decoded.getDisplayName());
    }

    @Test
    public void namesMayContainSpacesAndNonAscii() throws Exception {
        String name = "Ana Sofia";
        HelloPayload decoded = HelloPayload.decode(HelloPayload.encode(keys.getPublic(), name));
        assertEquals(name, decoded.getDisplayName());
    }

    @Test
    public void anEmptyNameIsAllowed() throws Exception {
        HelloPayload decoded = HelloPayload.decode(HelloPayload.encode(keys.getPublic(), ""));
        assertEquals("", decoded.getDisplayName());
        assertEquals(keys.getPublic(), decoded.getPublicKey());
    }

    /**
     * A v1 peer sent the bare X.509 key with no length prefix. Such a HELLO must still yield a
     * usable key rather than being dropped, so an older client can still be talked to.
     */
    @Test
    public void bareV1KeyStillDecodes() throws Exception {
        HelloPayload decoded = HelloPayload.decode(keys.getPublic().getEncoded());
        assertEquals(keys.getPublic(), decoded.getPublicKey());
        assertEquals("", decoded.getDisplayName());
    }

    /** The name is rendered straight into the chat list; control characters must not survive. */
    @Test
    public void controlCharactersAreStripped() throws Exception {
        HelloPayload decoded = HelloPayload.decode(
                HelloPayload.encode(keys.getPublic(), "Ary\nan\tSmith\0"));
        assertEquals("Ary an Smith", decoded.getDisplayName());
    }

    /** Bidi overrides could make a name reorder the interface text around it. */
    @Test
    public void bidiOverridesAreStripped() throws Exception {
        HelloPayload decoded = HelloPayload.decode(
                HelloPayload.encode(keys.getPublic(), "evil‮reversed"));
        assertTrue(decoded.getDisplayName().indexOf('‮') < 0);
        assertEquals("evil reversed", decoded.getDisplayName());
    }

    @Test
    public void overlongNamesAreTruncatedToValidUtf8() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append('a');
        }
        HelloPayload decoded = HelloPayload.decode(
                HelloPayload.encode(keys.getPublic(), sb.toString()));
        assertTrue(decoded.getDisplayName().length() <= HelloPayload.MAX_NAME_BYTES);
    }

    @Test
    public void emptyPayloadIsRejected() {
        try {
            HelloPayload.decode(new byte[0]);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            // A HELLO with no body carries no key, so there is nothing to trust.
        }
    }

    @Test
    public void garbageIsRejectedRatherThanMisread() {
        byte[] junk = new byte[64];
        Arrays.fill(junk, (byte) 0x41);
        try {
            HelloPayload.decode(junk);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            // Must not surface a bogus key.
        }
    }
}
