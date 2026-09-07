package com.e2eechat.core.network;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers which certificate the client ends up trusting.
 *
 * <p>The interesting case is the one that cannot be reached from an ordinary test: a release build
 * must refuse to fall back to the development certificate, whose private key anyone can regenerate.
 * That decision is made from a classpath resource read in a static initialiser, so
 * {@link #aReleaseBuildRefusesTheDevelopmentCertificate()} loads a second copy of the classes
 * through an isolated class loader with a fabricated stamp in front of the real one. Asserting the
 * branch rather than the intent is the point - a fallback that silently returns to the dev
 * certificate in a shipped client is exactly the bug this guards.
 */
public class TlsSupportTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private String originalTrustStore;
    private String originalPassword;

    @Before
    public void captureProperties() {
        originalTrustStore = System.getProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        originalPassword = System.getProperty(TlsSupport.PASSWORD_PROPERTY);
    }

    @After
    public void restoreProperties() {
        restore(TlsSupport.TRUSTSTORE_PROPERTY, originalTrustStore);
        restore(TlsSupport.PASSWORD_PROPERTY, originalPassword);
        TlsSupport.resetCachedContext();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    // ------------------------------------------------------- configured store

    @Test
    public void aConfiguredTrustStoreIsUsed() throws Exception {
        File store = copyDevCertificateTo(temp.newFile("relay-truststore.p12"));
        System.setProperty(TlsSupport.TRUSTSTORE_PROPERTY, store.getAbsolutePath());

        KeyStore loaded = TlsSupport.loadTrustStore();
        assertNotNull(loaded);
        assertTrue("the configured store should contain the relay certificate",
                loaded.size() > 0);
    }

    /**
     * A configured-but-unusable truststore must fail, not quietly fall back. Falling back would
     * downgrade a deployment to the development certificate on nothing worse than a typo.
     */
    @Test
    public void aMissingConfiguredTrustStoreFailsRatherThanFallingBack() {
        File absent = new File(temp.getRoot(), "not-there.p12");
        System.setProperty(TlsSupport.TRUSTSTORE_PROPERTY, absent.getAbsolutePath());

        try {
            TlsSupport.loadTrustStore();
            fail("a missing configured truststore should not load");
        } catch (Exception e) {
            assertTrue("should name the missing file, was: " + e.getMessage(),
                    e.getMessage().contains("not-there.p12"));
        }
    }

    @Test
    public void anUnreadableConfiguredTrustStoreFailsRatherThanFallingBack() throws Exception {
        File garbage = temp.newFile("garbage.p12");
        Files.write(garbage.toPath(), "this is not a PKCS#12 file".getBytes("UTF-8"));
        System.setProperty(TlsSupport.TRUSTSTORE_PROPERTY, garbage.getAbsolutePath());

        try {
            TlsSupport.loadTrustStore();
            fail("an unreadable configured truststore should not load");
        } catch (Exception e) {
            assertTrue("should point at the configured store, was: " + e.getMessage(),
                    e.getMessage().contains("garbage.p12"));
        }
    }

    @Test
    public void noConfigurationMeansNoConfiguredPath() {
        System.clearProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        assertNull(TlsSupport.configuredTrustStorePath());
    }

    @Test
    public void theConfiguredPathIsReportedBack() {
        System.setProperty(TlsSupport.TRUSTSTORE_PROPERTY, "/some/relay.p12");
        assertEquals("/some/relay.p12", TlsSupport.configuredTrustStorePath());
    }

    /** The development fallback must keep working, or every developer's build breaks. */
    @Test
    public void aDevelopmentBuildStillFallsBackToTheBundledCertificate() throws Exception {
        System.clearProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        KeyStore loaded = TlsSupport.loadTrustStore();
        assertNotNull("a development build should still find the dev certificate", loaded);
    }

    // --------------------------------------------------------- release branch

    /**
     * The security property: with no truststore configured, a build stamped {@code release} must
     * refuse rather than trust a certificate whose private key is public.
     */
    @Test
    public void aReleaseBuildRefusesTheDevelopmentCertificate() throws Exception {
        String message = loadTrustStoreUnderStampedChannel("release");
        assertNotNull("a release build with no truststore should have failed", message);
        assertTrue("the failure should say what to set, was: " + message,
                message.contains(TlsSupport.TRUSTSTORE_PROPERTY));
    }

    /** The same harness against a dev stamp, proving the refusal comes from the channel. */
    @Test
    public void aDevelopmentStampDoesNotRefuse() throws Exception {
        assertNull("a dev build should not refuse the bundled certificate",
                loadTrustStoreUnderStampedChannel("dev"));
    }

    /** An unrecognised channel must read as development, never as release. */
    @Test
    public void anUnrecognisedChannelIsTreatedAsDevelopment() throws Exception {
        assertNull("an unknown channel should behave like development",
                loadTrustStoreUnderStampedChannel("wobble"));
    }

    /**
     * Loads a private copy of the classes with {@code build-info.properties} ahead of the real one
     * and calls {@code loadTrustStore()} with no truststore configured.
     *
     * @return the failure message, or {@code null} if the call succeeded
     */
    private String loadTrustStoreUnderStampedChannel(String channel) throws Exception {
        String previous = System.getProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        System.clearProperty(TlsSupport.TRUSTSTORE_PROPERTY);

        File stampDir = temp.newFolder("stamp-" + channel);
        writeStamp(new File(stampDir, "build-info.properties"), channel);

        // The stamp directory goes first so its build-info.properties shadows the generated one.
        List<URL> urls = new ArrayList<URL>();
        urls.add(stampDir.toURI().toURL());
        for (String entry : System.getProperty("java.class.path")
                .split(File.pathSeparator)) {
            urls.add(new File(entry).toURI().toURL());
        }

        // A null parent forces this loader to define its own BuildInfo and TlsSupport rather than
        // inheriting the ones this test already triggered the static initialiser on.
        URLClassLoader isolated =
                new URLClassLoader(urls.toArray(new URL[0]), null);
        try {
            Class<?> tls = isolated.loadClass(TlsSupport.class.getName());
            Method load = tls.getMethod("loadTrustStore");
            try {
                load.invoke(null);
                return null;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                return cause.getMessage() == null ? cause.toString() : cause.getMessage();
            }
        } finally {
            isolated.close();
            restore(TlsSupport.TRUSTSTORE_PROPERTY, previous);
        }
    }

    private static void writeStamp(File file, String channel) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            String body = "version=9.9.9\ncommit=testcommit\nbuildTime=1970-01-01T00:00:00Z\n"
                    + "channel=" + channel + "\n";
            out.write(body.getBytes("UTF-8"));
        }
    }

    /** Copies the generated development certificate somewhere a test can point at it. */
    private static File copyDevCertificateTo(File destination) throws IOException {
        File source = null;
        String[] candidates = {
            "chat-server/src/main/resources/dev-keystore.p12",
            "../chat-server/src/main/resources/dev-keystore.p12",
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                source = file;
                break;
            }
        }
        assertNotNull("dev-keystore.p12 not found - run scripts/generate-dev-cert.sh", source);

        try (InputStream in = Files.newInputStream(source.toPath());
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        return destination;
    }
}
