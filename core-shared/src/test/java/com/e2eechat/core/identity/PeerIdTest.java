package com.e2eechat.core.identity;

import com.e2eechat.core.crypto.RSAUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PeerIdTest {

    private static KeyPair alice;
    private static KeyPair bob;

    @BeforeClass
    public static void generateKeys() throws Exception {
        alice = RSAUtils.generateKeyPair();
        bob = RSAUtils.generateKeyPair();
    }

    @Test
    public void idIsDerivedOnlyFromTheKey() {
        assertEquals(PeerId.of(alice.getPublic()), PeerId.of(alice.getPublic()));
        assertNotEquals(PeerId.of(alice.getPublic()), PeerId.of(bob.getPublic()));
    }

    @Test
    public void idIs128BitsOfLowercaseHex() {
        String id = PeerId.of(alice.getPublic());
        assertEquals(32, id.length());
        assertEquals(PeerId.ID_LENGTH, id.length());
        assertTrue(id.matches("[0-9a-f]{32}"));
    }

    /**
     * The point of the change: a rename must not move the address. The id has no name in it, so
     * this is structural rather than behavioural - there is no input to vary.
     */
    @Test
    public void idCarriesNoDisplayName() {
        String id = PeerId.of(alice.getPublic());
        assertFalse(id.contains("@"));
        assertFalse(id.contains("aryan"));
    }

    @Test
    public void validationAcceptsOnlyCanonicalIds() {
        assertTrue(PeerId.isValid(PeerId.of(alice.getPublic())));
        assertFalse(PeerId.isValid(null));
        assertFalse(PeerId.isValid(""));
        assertFalse(PeerId.isValid("aryan@A291:474"));
        assertFalse(PeerId.isValid("0123456789abcdef0123456789abcde"));   // 31 chars
        assertFalse(PeerId.isValid("0123456789abcdef0123456789abcdef0")); // 33 chars
        assertFalse(PeerId.isValid("0123456789ABCDEF0123456789abcdef"));  // uppercase
        assertFalse(PeerId.isValid("0123456789abcdefg123456789abcdef"));  // non-hex
    }

    @Test
    public void parseAcceptsWhatUsersActuallyPaste() {
        String id = PeerId.of(alice.getPublic());
        assertEquals(id, PeerId.parse(id));
        assertEquals(id, PeerId.parse("  " + id + "  "));
        assertEquals(id, PeerId.parse(id.toUpperCase()));
        assertEquals(id, PeerId.parse(PeerId.forDisplay(id)));
        assertNull(PeerId.parse("aryan@A291:474"));
        assertNull(PeerId.parse("not an id"));
    }

    @Test
    public void displayFormRoundTrips() {
        String id = PeerId.of(alice.getPublic());
        String shown = PeerId.forDisplay(id);
        assertEquals(3, shown.length() - shown.replace("-", "").length());
        assertEquals(id, PeerId.parse(shown));
    }

    @Test
    public void shortFormIsTheFirstBlock() {
        String id = PeerId.of(alice.getPublic());
        assertEquals(id.substring(0, 8), PeerId.shortForm(id));
        assertEquals("unknown", PeerId.shortForm(null));
        assertEquals("unknown", PeerId.shortForm(""));
    }

    @Test
    public void legacyIdsAreRecognisedRatherThanMistakenForValid() {
        assertTrue(PeerId.isLegacyFormat("aryan@A291:474"));
        assertTrue(PeerId.isLegacyFormat("Me@A291:474"));
        assertFalse(PeerId.isLegacyFormat(PeerId.of(alice.getPublic())));
        assertFalse(PeerId.isLegacyFormat(null));
        assertFalse(PeerId.isLegacyFormat("@leading"));
    }

    /**
     * The old id kept 7 hex digits, about 28 bits, which collides around 16k identities. Guard the
     * width so nobody quietly shortens it again.
     */
    @Test
    public void idIsWideEnoughThatCollisionsAreNotAConcern() {
        assertTrue("id must be at least 128 bits", PeerId.ID_BYTES * 8 >= 128);
    }
}
