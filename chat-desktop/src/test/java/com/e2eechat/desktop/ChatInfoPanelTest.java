package com.e2eechat.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The formatting of a safety number.
 *
 * <p>Headless: this is the part that has to be right, and it is pure text. Two people read these
 * numbers aloud to each other a group at a time, which a single unbroken run of hex defeats.
 */
public class ChatInfoPanelTest {

    @Test
    public void aFingerprintIsSplitIntoReadableGroups() {
        String grouped = ChatInfoPanel.groupFingerprint("AABBCCDDEEFF00112233445566778899");

        assertEquals("AABBC CDDEE FF001 12233 44556 67788 99", grouped);
    }

    @Test
    public void aMissingFingerprintSaysSoRatherThanShowingNothing() {
        assertTrue(ChatInfoPanel.groupFingerprint(null).length() > 0);
        assertEquals("(no key received yet)", ChatInfoPanel.groupFingerprint(null));
    }

    @Test
    public void groupingLeavesNoCharacterBehind() {
        String source = "0123456789ABCDEF0123456789ABCDEF";

        String grouped = ChatInfoPanel.groupFingerprint(source);

        assertEquals(source, grouped.replace(" ", ""));
    }
}
