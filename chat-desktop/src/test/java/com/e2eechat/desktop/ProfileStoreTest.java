package com.e2eechat.desktop;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File configDir;

    @Before
    public void setUp() throws Exception {
        configDir = tmp.newFolder("profile");
    }

    @Test
    public void nameSurvivesRestart() {
        new ProfileStore(configDir).setDisplayName("Aryan");

        // A second instance stands in for the next launch of the app.
        ProfileStore reopened = new ProfileStore(configDir);
        assertTrue(reopened.getDisplayName().isPresent());
        assertEquals("Aryan", reopened.getDisplayName().get());
    }

    /**
     * The regression this class exists for: the display name forms the first half of the peer id,
     * and it used to be rebuilt as "Me" on every launch after the first, silently changing the id
     * contacts route to.
     */
    @Test
    public void peerIdIsStableAcrossLaunches() {
        String fingerprintPrefix = "A291:474";

        new ProfileStore(configDir).setDisplayName("Aryan");
        String firstLaunch = new ProfileStore(configDir).getDisplayName().get()
                + "@" + fingerprintPrefix;
        String secondLaunch = new ProfileStore(configDir).getDisplayName().get()
                + "@" + fingerprintPrefix;

        assertEquals(firstLaunch, secondLaunch);
        assertEquals("Aryan@A291:474", secondLaunch);
    }

    @Test
    public void absentProfileReportsNoName() {
        assertFalse(new ProfileStore(configDir).getDisplayName().isPresent());
    }

    @Test
    public void blankNameIsTreatedAsAbsent() {
        ProfileStore store = new ProfileStore(configDir);
        store.setDisplayName("   ");
        assertFalse(store.getDisplayName().isPresent());
    }

    @Test
    public void atSignIsStrippedSoPeerIdsStaySplittable() {
        // clientId is split on the first '@'; a name containing one would corrupt the parse.
        ProfileStore store = new ProfileStore(configDir);
        store.setDisplayName("ary@n");
        assertEquals("aryn", store.getDisplayName().get());

        String clientId = store.getDisplayName().get() + "@" + "A291:474";
        assertEquals("aryn", clientId.substring(0, clientId.indexOf('@')));
    }

    @Test
    public void longNamesAreCapped() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append('x');
        }
        ProfileStore store = new ProfileStore(configDir);
        store.setDisplayName(sb.toString());
        assertEquals(64, store.getDisplayName().get().length());
    }

    @Test
    public void rewritingReplacesThePreviousName() {
        ProfileStore store = new ProfileStore(configDir);
        store.setDisplayName("First");
        store.setDisplayName("Second");
        assertEquals("Second", new ProfileStore(configDir).getDisplayName().get());
    }

    @Test
    public void validityMatchesNormalisation() {
        assertTrue(ProfileStore.isValid("Bob"));
        assertFalse(ProfileStore.isValid(""));
        assertFalse(ProfileStore.isValid("   "));
        assertFalse(ProfileStore.isValid("@"));
        assertFalse(ProfileStore.isValid(null));
    }
}
