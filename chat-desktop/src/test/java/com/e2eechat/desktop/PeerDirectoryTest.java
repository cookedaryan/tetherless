package com.e2eechat.desktop;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Peer names and verification.
 *
 * <p>Verification is kept in its own file. peer-names.properties carries a header saying its
 * contents are claims made by peers about themselves; verification is the opposite - the local
 * user's own judgement - and putting the two in one file would make that header untrue.
 */
public class PeerDirectoryTest {

    private static final String PEER = "4f3a91c28b7e05d6a1b2c3d4e5f60718";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File home;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("profile");
    }

    @Test
    public void aPeerIsNotVerifiedUntilSomebodySaysSo() {
        assertFalse(new PeerDirectory(home).isVerified(PEER));
    }

    @Test
    public void verificationSurvivesARestart() {
        new PeerDirectory(home).setVerified(PEER, true);

        assertTrue(new PeerDirectory(home).isVerified(PEER));
    }

    @Test
    public void verificationCanBeWithdrawn() {
        PeerDirectory directory = new PeerDirectory(home);
        directory.setVerified(PEER, true);

        directory.setVerified(PEER, false);

        assertFalse(directory.isVerified(PEER));
        assertFalse(new PeerDirectory(home).isVerified(PEER));
    }

    /** Verifying one peer says nothing about another. */
    @Test
    public void verificationIsPerPeer() {
        PeerDirectory directory = new PeerDirectory(home);
        directory.setVerified(PEER, true);

        assertFalse(directory.isVerified("0000000000000000000000000000dead"));
    }

    @Test
    public void namesAndVerificationDoNotDisturbEachOther() {
        PeerDirectory directory = new PeerDirectory(home);

        directory.setName(PEER, "Bob");
        directory.setVerified(PEER, true);

        PeerDirectory reopened = new PeerDirectory(home);
        assertEquals("Bob", reopened.nameFor(PEER));
        assertTrue(reopened.isVerified(PEER));
    }

    @Test
    public void anUnknownPeerFallsBackToTheShortFormOfItsId() {
        assertEquals(PEER.substring(0, 8), new PeerDirectory(home).nameFor(PEER));
    }
}
