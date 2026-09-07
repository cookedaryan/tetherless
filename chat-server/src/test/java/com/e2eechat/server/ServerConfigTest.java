package com.e2eechat.server;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers what the relay will and will not serve TLS with.
 *
 * <p>The development keystore is both the default and reproducible by anyone who runs
 * {@code scripts/generate-dev-cert.sh}, so an operator who simply started the jar would have been
 * served a relay identity that is trivially impersonated, with nothing on screen to say so. The
 * refusal is reached from a build stamp read in a static initialiser, so the release cases load a
 * second copy of the classes through an isolated class loader with a fabricated stamp.
 */
public class ServerConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String PASSWORD_FILE_PROPERTY = "server.keystore_password_file";
    private static final String PASSWORD_PROPERTY = "server.keystore_password";
    private static final String PATH_PROPERTY = "server.keystore_path";

    private final String[] keys = {PASSWORD_FILE_PROPERTY, PASSWORD_PROPERTY, PATH_PROPERTY};
    private final String[] originals = new String[3];

    @Before
    public void captureProperties() {
        for (int i = 0; i < keys.length; i++) {
            originals[i] = System.getProperty(keys[i]);
            System.clearProperty(keys[i]);
        }
    }

    @After
    public void restoreProperties() {
        for (int i = 0; i < keys.length; i++) {
            if (originals[i] == null) {
                System.clearProperty(keys[i]);
            } else {
                System.setProperty(keys[i], originals[i]);
            }
        }
    }

    // ------------------------------------------------------------- development

    /** Developers must keep being able to just run it. */
    @Test
    public void aDevelopmentBuildAcceptsTheDefaultKeystore() {
        ServerConfig config = new ServerConfig();
        assertEquals(ServerConfig.DEFAULT_KEYSTORE_PATH, config.getKeystorePath());
        config.verifyTlsConfiguration();
    }

    @Test
    public void aConfiguredKeystoreIsUsed() {
        System.setProperty(PATH_PROPERTY, "/etc/tetherless/relay.p12");
        assertEquals("/etc/tetherless/relay.p12", new ServerConfig().getKeystorePath());
    }

    // ------------------------------------------------------------ password file

    @Test
    public void thePasswordIsReadFromAFileWhenOneIsConfigured() throws Exception {
        File passwordFile = temp.newFile("keystore-password");
        write(passwordFile, "s3cret-from-a-file");
        System.setProperty(PASSWORD_FILE_PROPERTY, passwordFile.getAbsolutePath());

        assertEquals("s3cret-from-a-file", new ServerConfig().getKeystorePassword());
    }

    /** Shell redirection almost always leaves a trailing newline; it is not part of the password. */
    @Test
    public void surroundingWhitespaceIsStripped() throws Exception {
        File passwordFile = temp.newFile("keystore-password");
        write(passwordFile, "  s3cret\n");
        System.setProperty(PASSWORD_FILE_PROPERTY, passwordFile.getAbsolutePath());

        assertEquals("s3cret", new ServerConfig().getKeystorePassword());
    }

    /** The file wins: it exists precisely to keep the secret out of the environment. */
    @Test
    public void thePasswordFileBeatsThePasswordProperty() throws Exception {
        File passwordFile = temp.newFile("keystore-password");
        write(passwordFile, "from-file");
        System.setProperty(PASSWORD_FILE_PROPERTY, passwordFile.getAbsolutePath());
        System.setProperty(PASSWORD_PROPERTY, "from-property");

        assertEquals("from-file", new ServerConfig().getKeystorePassword());
    }

    /** Silently starting with the wrong password would fail later and far less clearly. */
    @Test
    public void aMissingPasswordFileFailsLoudly() {
        System.setProperty(PASSWORD_FILE_PROPERTY,
                new File(temp.getRoot(), "absent").getAbsolutePath());
        try {
            new ServerConfig();
            fail("a missing password file should not be ignored");
        } catch (IllegalStateException e) {
            assertTrue("should name the file, was: " + e.getMessage(),
                    e.getMessage().contains("KEYSTORE_PASSWORD_FILE"));
        }
    }

    // ---------------------------------------------------------------- release

    /** The security property: a packaged relay must not serve the development keypair. */
    @Test
    public void aReleaseBuildRefusesTheDefaultKeystore() throws Exception {
        String message = verifyUnderStampedChannel("release", null);
        assertNotNull("a release build with no keystore should have failed", message);
        assertTrue("the failure should say what to set, was: " + message,
                message.contains("KEYSTORE_PATH"));
    }

    /** ...but accepts one that was actually configured. */
    @Test
    public void aReleaseBuildAcceptsAConfiguredKeystore() throws Exception {
        assertNull("a configured keystore should be accepted in a release build",
                verifyUnderStampedChannel("release", "/etc/tetherless/relay.p12"));
    }

    @Test
    public void aDevelopmentStampDoesNotRefuse() throws Exception {
        assertNull(verifyUnderStampedChannel("dev", null));
    }

    /** Anything unrecognised must read as development, never as a release. */
    @Test
    public void anUnrecognisedChannelIsTreatedAsDevelopment() throws Exception {
        assertNull(verifyUnderStampedChannel("wobble", null));
    }

    /**
     * Builds a {@link ServerConfig} in an isolated class loader whose {@code build-info.properties}
     * declares the given channel, and calls {@code verifyTlsConfiguration()}.
     *
     * @param keystorePath a keystore to configure, or {@code null} to leave it defaulted
     * @return the failure message, or {@code null} if the call succeeded
     */
    private String verifyUnderStampedChannel(String channel, String keystorePath) throws Exception {
        File stampDir = temp.newFolder("stamp-" + channel + (keystorePath == null ? "" : "-set"));
        write(new File(stampDir, "build-info.properties"),
                "version=9.9.9\ncommit=test\nbuildTime=1970-01-01T00:00:00Z\nchannel="
                        + channel + "\n");

        // The stamp goes first so it shadows the generated one.
        List<URL> urls = new ArrayList<URL>();
        urls.add(stampDir.toURI().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            urls.add(new File(entry).toURI().toURL());
        }

        if (keystorePath != null) {
            System.setProperty(PATH_PROPERTY, keystorePath);
        }
        // A null parent forces this loader to define its own BuildInfo and ServerConfig rather than
        // inheriting ones whose static initialiser has already run.
        URLClassLoader isolated = new URLClassLoader(urls.toArray(new URL[0]), null);
        try {
            Class<?> type = isolated.loadClass(ServerConfig.class.getName());
            Object config = type.getDeclaredConstructor().newInstance();
            Method verify = type.getMethod("verifyTlsConfiguration");
            try {
                verify.invoke(config);
                return null;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                return cause.getMessage() == null ? cause.toString() : cause.getMessage();
            }
        } finally {
            isolated.close();
            System.clearProperty(PATH_PROPERTY);
        }
    }

    private static void write(File file, String body) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(body.getBytes("UTF-8"));
        }
    }
}
