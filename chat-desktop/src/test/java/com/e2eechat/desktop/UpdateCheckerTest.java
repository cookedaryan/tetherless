package com.e2eechat.desktop;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The startup update check.
 *
 * <p>No network: the release feed is a seam, so every case here - including the failures, which
 * are the ones that matter - is exercised deterministically. The rule the whole class is built
 * around is that nothing it can encounter is allowed to reach the user as an error.
 */
public class UpdateCheckerTest {

    private static final String REPOSITORY = "cookedaryan/tetherless";

    /** A minimal release body, shaped like the one GitHub returns. */
    private static String releaseJson(String tag) {
        return "{\"url\":\"https://api.github.com/repos/" + REPOSITORY + "/releases/1\","
                + "\"html_url\":\"https://github.com/" + REPOSITORY + "/releases/tag/" + tag + "\","
                + "\"author\":{\"login\":\"someone\","
                + "\"html_url\":\"https://github.com/someone\"},"
                + "\"tag_name\":\"" + tag + "\",\"name\":\"Release " + tag + "\","
                + "\"draft\":false,\"prerelease\":false}";
    }

    private static UpdateChecker checker(String currentVersion, UpdateChecker.ReleaseFeed feed) {
        return new UpdateChecker(currentVersion, REPOSITORY, feed);
    }

    private static UpdateChecker checkerReturning(String currentVersion, String tag) {
        return checker(currentVersion, () -> releaseJson(tag));
    }

    @Before
    public void enableChecks() {
        System.clearProperty(UpdateChecker.ENABLED_PROPERTY);
    }

    @After
    public void tidyUp() {
        System.clearProperty(UpdateChecker.ENABLED_PROPERTY);
    }

    // ------------------------------------------------------------- the happy path

    @Test
    public void aNewerReleaseIsReported() {
        Optional<UpdateChecker.Update> update = checkerReturning("1.0.0", "v1.2.0").check();

        assertTrue("a newer release should have been reported", update.isPresent());
        assertEquals("1.2.0", update.get().version());
    }

    /**
     * The link is built from the repository and tag, not lifted out of the body. The body carries
     * an author html_url too, and sending a user to that from a link the app vouched for would be
     * a small but real betrayal.
     */
    @Test
    public void theLinkPointsAtTheReleasePageForThatTag() {
        Optional<UpdateChecker.Update> update = checkerReturning("1.0.0", "v1.2.0").check();

        assertTrue(update.isPresent());
        assertEquals("https://github.com/cookedaryan/tetherless/releases/tag/v1.2.0",
                update.get().url());
    }

    @Test
    public void aTagWithoutTheVPrefixWorksToo() {
        Optional<UpdateChecker.Update> update = checkerReturning("1.0.0", "1.2.0").check();

        assertTrue(update.isPresent());
        assertEquals("1.2.0", update.get().version());
        assertEquals("https://github.com/cookedaryan/tetherless/releases/tag/1.2.0",
                update.get().url());
    }

    // ------------------------------------------------------- nothing worth saying

    @Test
    public void theSameVersionIsNotAnUpdate() {
        assertFalse(checkerReturning("1.0.0", "v1.0.0").check().isPresent());
    }

    @Test
    public void anOlderReleaseIsNotAnUpdate() {
        assertFalse(checkerReturning("2.0.0", "v1.9.9").check().isPresent());
    }

    /** A development build should be told about the release it leads to. */
    @Test
    public void aSnapshotIsOlderThanTheReleaseOfTheSameNumber() {
        Optional<UpdateChecker.Update> update = checkerReturning("1.0.0-SNAPSHOT", "v1.0.0").check();

        assertTrue(update.isPresent());
        assertEquals("1.0.0", update.get().version());
    }

    /** And a released build must never be offered a pre-release of what it already runs. */
    @Test
    public void aReleaseIsNotOfferedAPreReleaseOfItsOwnVersion() {
        assertFalse(checkerReturning("1.0.0", "v1.0.0-rc1").check().isPresent());
    }

    @Test
    public void missingComponentsCountAsZero() {
        assertFalse(checkerReturning("1.2.0", "v1.2").check().isPresent());
        assertTrue(checkerReturning("1.2", "v1.2.1").check().isPresent());
    }

    @Test
    public void doubleDigitComponentsCompareAsNumbersNotText() {
        assertTrue("1.10.0 is newer than 1.9.0",
                checkerReturning("1.9.0", "v1.10.0").check().isPresent());
        assertFalse("1.9.0 is not newer than 1.10.0",
                checkerReturning("1.10.0", "v1.9.0").check().isPresent());
    }

    // --------------------------------------------------------------- failing quietly

    @Test
    public void aNetworkFailureIsSilent() {
        UpdateChecker checker = checker("1.0.0", () -> {
            throw new IOException("no route to host");
        });

        assertFalse(checker.check().isPresent());
    }

    @Test
    public void aRuntimeFailureInTheFeedIsSilent() {
        UpdateChecker checker = checker("1.0.0", () -> {
            throw new IllegalStateException("something unexpected");
        });

        assertFalse(checker.check().isPresent());
    }

    @Test
    public void anUnparseableBodyIsSilent() {
        assertFalse(checker("1.0.0", () -> "<html>502 Bad Gateway</html>").check().isPresent());
        assertFalse(checker("1.0.0", () -> "").check().isPresent());
        assertFalse(checker("1.0.0", () -> null).check().isPresent());
    }

    @Test
    public void aBodyWithNoTagIsSilent() {
        assertFalse(checker("1.0.0", () -> "{\"message\":\"Not Found\"}").check().isPresent());
    }

    @Test
    public void aNonsenseVersionIsSilent() {
        assertFalse(checkerReturning("1.0.0", "vNightly").check().isPresent());
        assertFalse(checkerReturning("1.0.0", "").check().isPresent());
    }

    /** An unstamped build - run from an IDE - has no version to compare, so it must not ask. */
    @Test
    public void anUnstampedBuildDoesNotEvenAsk() {
        AtomicInteger fetches = new AtomicInteger();
        UpdateChecker checker = checker("unknown", () -> {
            fetches.incrementAndGet();
            return releaseJson("v9.9.9");
        });

        assertFalse(checker.check().isPresent());
        assertEquals("an unstamped build should not have called out", 0, fetches.get());
    }

    // ------------------------------------------------------------------ switched off

    @Test
    public void theCheckCanBeSwitchedOff() throws Exception {
        System.setProperty(UpdateChecker.ENABLED_PROPERTY, "false");
        AtomicInteger fetches = new AtomicInteger();
        UpdateChecker checker = checker("1.0.0", () -> {
            fetches.incrementAndGet();
            return releaseJson("v9.9.9");
        });

        checker.checkInBackground(update -> { });
        Thread.sleep(200);

        assertFalse(UpdateChecker.isEnabled());
        assertEquals("a disabled check must not reach the network", 0, fetches.get());
    }

    @Test
    public void theCheckIsOnUnlessSwitchedOff() {
        assertTrue(UpdateChecker.isEnabled());
        System.setProperty(UpdateChecker.ENABLED_PROPERTY, "true");
        assertTrue(UpdateChecker.isEnabled());
    }

    // ------------------------------------------------------------------ background

    @Test
    public void theBackgroundCheckReportsOffTheCallingThread() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<UpdateChecker.Update> seen = new AtomicReference<>();
        AtomicReference<String> callerThread = new AtomicReference<>();

        checkerReturning("1.0.0", "v2.0.0").checkInBackground(update -> {
            seen.set(update);
            callerThread.set(Thread.currentThread().getName());
            reported.countDown();
        });

        assertTrue("the callback never ran", reported.await(5, TimeUnit.SECONDS));
        assertNotNull(seen.get());
        assertEquals("2.0.0", seen.get().version());
        // The banner it drives is a Swing component, so the callback has to land on the EDT.
        assertTrue("callback ran on " + callerThread.get(),
                callerThread.get().startsWith("AWT-EventQueue"));
    }

    /** A hung or failing request must not produce a callback, and must not throw at the caller. */
    @Test
    public void thereIsNoCallbackWhenThereIsNoUpdate() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();

        checker("1.0.0", () -> {
            throw new IOException("timed out");
        }).checkInBackground(update -> callbacks.incrementAndGet());
        checkerReturning("2.0.0", "v1.0.0").checkInBackground(update -> callbacks.incrementAndGet());
        Thread.sleep(300);

        assertEquals(0, callbacks.get());
    }

    // ------------------------------------------------------------------ url shapes

    @Test
    public void theEndpointIsTheLatestReleaseForTheConfiguredRepository() {
        assertEquals("https://api.github.com/repos/cookedaryan/tetherless/releases/latest",
                UpdateChecker.latestReleaseUrl(REPOSITORY));
    }
}
