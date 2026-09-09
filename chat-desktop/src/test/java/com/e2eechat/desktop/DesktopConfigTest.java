package com.e2eechat.desktop;

import com.e2eechat.core.network.TlsSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers how the client decides where to connect and what to trust.
 *
 * <p>The truststore cases matter most: a deployment configures its pinned certificate here, and a
 * path that silently fails to take effect would leave a packaged build unable to connect at all -
 * or, worse, would have left it on the development certificate before that fallback was closed.
 */
public class DesktopConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String[] TOUCHED = {
        "tetherless.host",
        "tetherless.port",
        TlsSupport.TRUSTSTORE_PROPERTY,
        TlsSupport.PASSWORD_PROPERTY,
    };

    private final String[] originals = new String[TOUCHED.length];

    @Before
    public void captureProperties() {
        for (int i = 0; i < TOUCHED.length; i++) {
            originals[i] = System.getProperty(TOUCHED[i]);
            System.clearProperty(TOUCHED[i]);
        }
    }

    @After
    public void restoreProperties() {
        for (int i = 0; i < TOUCHED.length; i++) {
            if (originals[i] == null) {
                System.clearProperty(TOUCHED[i]);
            } else {
                System.setProperty(TOUCHED[i], originals[i]);
            }
        }
        DesktopConfig.clearUpdateChecksPreference();
    }

    // ------------------------------------------------------------ host / port

    @Test
    public void defaultsApplyWithNoConfiguration() throws Exception {
        DesktopConfig config = DesktopConfig.load(temp.newFolder("empty"), new String[0]);
        assertEquals("localhost", config.host());
        assertEquals(8080, config.port());
    }

    @Test
    public void theConfigFileIsRead() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "host=relay.example.org\nport=9443\n");

        DesktopConfig config = DesktopConfig.load(dir, new String[0]);
        assertEquals("relay.example.org", config.host());
        assertEquals(9443, config.port());
    }

    @Test
    public void argumentsBeatTheConfigFile() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "host=relay.example.org\nport=9443\n");

        DesktopConfig config = DesktopConfig.load(dir, new String[]{"other.host", "7000"});
        assertEquals("other.host", config.host());
        assertEquals(7000, config.port());
    }

    @Test
    public void systemPropertiesBeatTheConfigFile() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "host=from.file\n");
        System.setProperty("tetherless.host", "from.property");

        assertEquals("from.property", DesktopConfig.load(dir, new String[0]).host());
    }

    /** A bad port should not stop the app starting. */
    @Test
    public void anUnparseablePortFallsBackToTheDefault() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "port=not-a-number\n");
        assertEquals(8080, DesktopConfig.load(dir, new String[0]).port());
    }

    @Test
    public void anOutOfRangePortFallsBackToTheDefault() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "port=99999\n");
        assertEquals(8080, DesktopConfig.load(dir, new String[0]).port());
    }

    /** A corrupt config file must not be fatal either. */
    @Test
    public void anUnreadableConfigFileFallsBackToDefaults() throws Exception {
        File dir = temp.newFolder("profile");
        File configFile = new File(dir, "config.properties");
        try (OutputStream out = new FileOutputStream(configFile)) {
            out.write(new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x00});
        }
        DesktopConfig config = DesktopConfig.load(dir, new String[0]);
        assertEquals("localhost", config.host());
    }

    // ------------------------------------------------------------- truststore

    @Test
    public void noTrustStoreIsConfiguredByDefault() throws Exception {
        DesktopConfig config = DesktopConfig.load(temp.newFolder("empty"), new String[0]);
        assertNull(config.trustStorePath());

        config.applyTlsProperties();
        assertNull("nothing should be published when nothing is configured",
                System.getProperty(TlsSupport.TRUSTSTORE_PROPERTY));
    }

    @Test
    public void anAbsoluteTrustStorePathIsUsedAsGiven() throws Exception {
        File dir = temp.newFolder("profile");
        File store = temp.newFile("relay.p12");
        writeConfig(dir, "truststore=" + store.getAbsolutePath().replace("\\", "\\\\") + "\n");

        DesktopConfig config = DesktopConfig.load(dir, new String[0]);
        assertEquals(store.getAbsolutePath(), config.trustStorePath());
    }

    /** A bare filename should mean "beside my profile", not "wherever the app was launched from". */
    @Test
    public void aRelativeTrustStorePathResolvesAgainstTheProfileDirectory() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "truststore=relay.p12\n");

        DesktopConfig config = DesktopConfig.load(dir, new String[0]);
        assertEquals(new File(dir, "relay.p12").getPath(), config.trustStorePath());
    }

    /**
     * The whole point of the class: what the file says has to reach {@code TlsSupport}, which only
     * reads system properties.
     */
    @Test
    public void applyingPublishesTheTrustStoreWhereTlsSupportLooks() throws Exception {
        File dir = temp.newFolder("profile");
        File store = temp.newFile("relay.p12");
        writeConfig(dir, "truststore=" + store.getAbsolutePath().replace("\\", "\\\\") + "\n"
                + "truststore.password=hunter2\n");

        DesktopConfig.load(dir, new String[0]).applyTlsProperties();

        assertEquals(store.getAbsolutePath(), TlsSupport.configuredTrustStorePath());
        assertTrue("the configured password should be the one TlsSupport uses",
                new String(TlsSupport.trustStorePassword()).equals("hunter2"));
    }

    @Test
    public void anExplicitPropertyBeatsTheConfigFile() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "truststore=from-file.p12\n");
        System.setProperty(TlsSupport.TRUSTSTORE_PROPERTY, "/explicit/relay.p12");

        assertEquals("/explicit/relay.p12",
                DesktopConfig.load(dir, new String[0]).trustStorePath());
    }

    // -------------------------------------------------------- update checks

    /**
     * The toggle writes to Preferences, which sits one rung above the built-in default: a
     * deployment that sets updates=false keeps it off and the toggle cannot override it.
     */
    @Test
    public void anExplicitSettingBeatsThePreference() throws Exception {
        DesktopConfig.setUpdateChecksPreference(true);
        System.setProperty(UpdateChecker.ENABLED_PROPERTY, "false");
        try {
            assertFalse(DesktopConfig.load(temp.getRoot(), new String[0]).updateChecks());
        } finally {
            System.clearProperty(UpdateChecker.ENABLED_PROPERTY);
        }
    }

    /**
     * The headline example of the precedence chain, and the one rung that had no test.
     *
     * <p>An installation that puts {@code updates=false} in {@code config.properties} has decided
     * for this machine. The settings toggle sits below that deliberately and must not be able to
     * turn the check back on.
     */
    @Test
    public void theConfigFileBeatsThePreference() throws Exception {
        File dir = temp.newFolder("profile");
        writeConfig(dir, "updates=false\n");
        DesktopConfig.setUpdateChecksPreference(true);

        assertFalse("config.properties was overridden by the settings toggle",
                DesktopConfig.load(dir, new String[0]).updateChecks());
    }

    /**
     * What the settings sheet has to render.
     *
     * <p>The sheet used to show the stored preference, so a client with {@code updates=false} in
     * its configuration showed the switch <strong>on</strong> while never checking for anything.
     * It needs both halves: the value actually in force, and whether a rung above the toggle is
     * the one deciding it - because in that case flipping the switch would do nothing.
     */
    @Test
    public void aConfiguredSettingIsReportedAsPinnedAndInForce() throws Exception {
        File dir = temp.newFolder("pinned");
        writeConfig(dir, "updates=false\n");
        DesktopConfig.setUpdateChecksPreference(true);

        DesktopConfig.load(dir, new String[0]);

        assertEquals(Boolean.FALSE, DesktopConfig.updateChecksOverride());
        assertFalse("the sheet would show a switch that is on for a client that never checks",
                DesktopConfig.effectiveUpdateChecks());
    }

    /** With nothing above it, the toggle is what decides and the sheet may offer it. */
    @Test
    public void nothingIsPinnedWhenOnlyTheToggleHasAnOpinion() throws Exception {
        DesktopConfig.setUpdateChecksPreference(false);

        DesktopConfig.load(temp.newFolder("plain"), new String[0]);

        assertNull(DesktopConfig.updateChecksOverride());
        assertFalse(DesktopConfig.effectiveUpdateChecks());
    }

    @Test
    public void thePreferenceDecidesWhenNothingElseIsConfigured() {
        DesktopConfig.setUpdateChecksPreference(false);
        assertFalse(DesktopConfig.load(temp.getRoot(), new String[0]).updateChecks());

        DesktopConfig.setUpdateChecksPreference(true);
        assertTrue(DesktopConfig.load(temp.getRoot(), new String[0]).updateChecks());
    }

    @Test
    public void updateChecksAreOnWhenNothingHasEverBeenSet() {
        DesktopConfig.clearUpdateChecksPreference();
        assertTrue(DesktopConfig.load(temp.getRoot(), new String[0]).updateChecks());
    }

    private static void writeConfig(File dir, String body) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(dir, "config.properties"))) {
            out.write(body.getBytes("UTF-8"));
        }
    }
}
