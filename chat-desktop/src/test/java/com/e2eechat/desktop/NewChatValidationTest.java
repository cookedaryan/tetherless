package com.e2eechat.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Validating a pasted peer id.
 *
 * <p>Pulled out as plain functions so the rules can be tested without a window. The rules
 * themselves are unchanged; what changes is that the answer appears under the field rather than in
 * a second window you must dismiss before you can correct what you typed.
 */
public class NewChatValidationTest {

    private static final String OWN = "0000000000000000000000000000beef";
    private static final String PEER = "4f3a91c28b7e05d6a1b2c3d4e5f60718";

    @Test
    public void aWellFormedIdIsAccepted() {
        assertEquals(PEER, ConversationListPanel.validateNewChatId(PEER, OWN));
        assertNull(ConversationListPanel.newChatError(PEER, OWN));
    }

    /** Accept whatever form was pasted; routing compares the canonical id byte for byte. */
    @Test
    public void groupedAndUpperCaseFormsAreAccepted() {
        assertEquals(PEER,
                ConversationListPanel.validateNewChatId("4F3A91C2 8B7E05D6 A1B2C3D4 E5F60718", OWN));
    }

    @Test
    public void somethingThatIsNotAnIdIsRejectedWithAReason() {
        assertNull(ConversationListPanel.validateNewChatId("hello", OWN));

        String error = ConversationListPanel.newChatError("hello", OWN);
        assertNotNull(error);
        assertEquals("That is not a peer id. An id is 32 hex characters.", error);
    }

    @Test
    public void yourOwnIdIsRejectedWithADifferentReason() {
        assertNull(ConversationListPanel.validateNewChatId(OWN, OWN));
        assertEquals("That is your own id.", ConversationListPanel.newChatError(OWN, OWN));
    }

    @Test
    public void anEmptyFieldIsNotAnError() {
        assertNull(ConversationListPanel.newChatError("", OWN));
        assertNull(ConversationListPanel.newChatError("   ", OWN));
        assertNull(ConversationListPanel.newChatError(null, OWN));
    }
}
