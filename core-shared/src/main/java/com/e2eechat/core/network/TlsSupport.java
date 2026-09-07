package com.e2eechat.core.network;

import com.e2eechat.core.build.BuildInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Builds the client-side {@link SSLContext} that pins the relay's certificate.
 *
 * <p>This exists because the same twelve-line load-the-truststore block had been copied into
 * {@link ConnectionManager} and four separate server tests. Each copy was independently flagged by
 * SpotBugs and each would have needed fixing separately the next time the trust setup changed.
 *
 * <h2>Which certificate gets pinned</h2>
 * A deployment supplies its own truststore through {@link #TRUSTSTORE_PROPERTY} or
 * {@link #TRUSTSTORE_ENV}. Nothing else is consulted when one is configured: if the file is missing
 * or will not open, the connection fails rather than quietly falling back to something else, because
 * a silent downgrade of trust is the failure this class exists to prevent.
 *
 * <p>With nothing configured, a development build falls back to the bundled {@code dev-keystore.p12}
 * and says so loudly in the log. <strong>A release build refuses.</strong> The development
 * certificate is reproducible by anyone who runs {@code scripts/generate-dev-cert.sh}, so its
 * private key is effectively public; an installed client that trusted it could be intercepted by
 * anybody. {@link BuildInfo} decides which case applies, and treats anything it cannot positively
 * identify as a development build, so a corrupted stamp cannot grant a shipped artifact the
 * fallback.
 *
 * <h2>On the truststore password</h2>
 * The password here protects a <em>truststore</em>, which holds only public certificates. Unlike a
 * keystore password it guards integrity, not confidentiality - it stops someone appending a rogue
 * CA to the file, and reveals nothing if disclosed. It is still read from configuration rather than
 * frozen into the bytecode, mirroring how {@code ServerConfig} handles the server's keystore
 * password, so a deployment that ships a real pinned certificate can set its own.
 */
public final class TlsSupport {

    private static final Logger LOG = LoggerFactory.getLogger(TlsSupport.class);

    /** System property holding the truststore password. */
    public static final String PASSWORD_PROPERTY = "tetherless.truststore.password";

    /** Environment variable holding the truststore password. */
    public static final String PASSWORD_ENV = "TETHERLESS_TRUSTSTORE_PASSWORD";

    /** System property holding the path to the deployment's truststore. */
    public static final String TRUSTSTORE_PROPERTY = "tetherless.truststore";

    /** Environment variable holding the path to the deployment's truststore. */
    public static final String TRUSTSTORE_ENV = "TETHERLESS_TRUSTSTORE";

    /** Classpath resource name for the bundled development certificate. */
    private static final String RESOURCE = "dev-keystore.p12";

    /**
     * Paths tried when the truststore is not on the classpath, covering the working directories
     * Gradle uses for the desktop app, the server, and tests run from the repository root.
     */
    private static final String[] FALLBACK_PATHS = {
        "chat-server/src/main/resources/" + RESOURCE,
        "../chat-server/src/main/resources/" + RESOURCE,
        "src/main/resources/" + RESOURCE,
    };

    private static final Object CONTEXT_LOCK = new Object();

    /** Lazily built and reused; see {@link #clientContext()}. */
    private static volatile SSLContext clientContext;

    private TlsSupport() {
    }

    /**
     * The configured truststore password, or the development default when nothing is set.
     *
     * @return a fresh array each call, so the caller may zero it without affecting other callers
     */
    public static char[] trustStorePassword() {
        String configured = System.getProperty(PASSWORD_PROPERTY);
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv(PASSWORD_ENV);
        }
        if (configured == null || configured.isEmpty()) {
            // Development default: the generated dev certificate uses the JDK's stock password.
            configured = "changeit";
        }
        return configured.toCharArray();
    }

    /**
     * The truststore path this deployment was configured with, or {@code null} for none.
     *
     * @return a filesystem path, or {@code null} when neither the property nor the variable is set
     */
    public static String configuredTrustStorePath() {
        String configured = System.getProperty(TRUSTSTORE_PROPERTY);
        if (configured == null || configured.isEmpty()) {
            configured = System.getenv(TRUSTSTORE_ENV);
        }
        return configured == null || configured.isEmpty() ? null : configured;
    }

    /**
     * Loads the truststore holding the certificate the relay is pinned to.
     *
     * @throws IllegalStateException if a release build has no truststore configured, or a
     *         configured one cannot be read
     */
    public static KeyStore loadTrustStore() throws Exception {
        String configured = configuredTrustStorePath();
        if (configured != null) {
            return loadConfigured(configured);
        }
        if (BuildInfo.isRelease()) {
            throw new IllegalStateException(
                    "No TLS truststore configured. A packaged build will not fall back to the "
                            + "development certificate, whose private key is reproducible by "
                            + "anyone. Set -D" + TRUSTSTORE_PROPERTY + "=<path to the relay's "
                            + "certificate> or the " + TRUSTSTORE_ENV + " environment variable.");
        }
        return loadDevelopmentCertificate();
    }

    /** Loads the deployment's own truststore. No fallback: a bad path is a hard failure. */
    private static KeyStore loadConfigured(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalStateException("Configured TLS truststore does not exist: "
                    + file.getAbsolutePath());
        }
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        char[] password = trustStorePassword();
        try (FileInputStream in = new FileInputStream(file)) {
            trustStore.load(in, password);
            LOG.info("Pinned the relay certificate from {}", file.getAbsolutePath());
            return trustStore;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the configured TLS truststore at "
                    + file.getAbsolutePath() + ". Check the path and "
                    + PASSWORD_PROPERTY + ".", e);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /** The bundled development certificate. Only ever reached from a non-release build. */
    private static KeyStore loadDevelopmentCertificate() throws Exception {
        LOG.warn("No truststore configured; falling back to the bundled development certificate. "
                + "Its private key is reproducible from scripts/generate-dev-cert.sh and offers no "
                + "protection against interception. Never use this outside development.");

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        char[] password = trustStorePassword();
        try {
            try (InputStream resource =
                         TlsSupport.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (resource != null) {
                    trustStore.load(resource, password);
                    return trustStore;
                }
            }

            Exception lastFailure = null;
            for (String path : FALLBACK_PATHS) {
                try (FileInputStream fis = new FileInputStream(path)) {
                    trustStore.load(fis, password);
                    return trustStore;
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
            throw new IllegalStateException(
                    "Could not locate " + RESOURCE + " on the classpath or at any of "
                            + String.join(", ", FALLBACK_PATHS), lastFailure);
        } finally {
            // The password is no longer needed once the store is open.
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * A TLS 1.3 context that trusts only the pinned certificate.
     *
     * <p>Cached: the truststore cannot change while the JVM runs, and parsing the PKCS#12 file per
     * connection is expensive enough to matter. The server's concurrency test opens a thousand
     * sockets and was intermittently missing its deadline on truststore parsing alone.
     */
    public static SSLContext clientContext() throws Exception {
        SSLContext cached = clientContext;
        if (cached != null) {
            return cached;
        }
        synchronized (CONTEXT_LOCK) {
            if (clientContext == null) {
                TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(loadTrustStore());

                SSLContext created = SSLContext.getInstance("TLSv1.3");
                created.init(null, tmf.getTrustManagers(), null);
                clientContext = created;
            }
            return clientContext;
        }
    }

    /**
     * Drops the cached context so the next call rebuilds it.
     *
     * <p>Only useful to tests, which need to vary the configured truststore within one JVM. A
     * running client has no reason to call this - the truststore cannot change under it.
     */
    public static void resetCachedContext() {
        synchronized (CONTEXT_LOCK) {
            clientContext = null;
        }
    }

    /**
     * Opens a TLS 1.3 socket to the relay with the handshake already completed, so a caller that
     * gets a socket back knows the certificate was accepted.
     *
     * @param host relay hostname or address
     * @param port relay port
     */
    public static SSLSocket connectPinned(String host, int port) throws Exception {
        SSLSocketFactory factory = clientContext().getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);
        socket.setEnabledProtocols(new String[]{"TLSv1.3"});
        socket.startHandshake();
        return socket;
    }
}
