package com.e2eechat.core.network;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Builds the client-side {@link SSLContext} that pins the relay's development certificate.
 *
 * <p>This exists because the same twelve-line load-the-truststore block had been copied into
 * {@link ConnectionManager} and four separate server tests. Each copy was independently flagged by
 * SpotBugs and each would have needed fixing separately the next time the trust setup changed.
 *
 * <h2>On the truststore password</h2>
 * The password here protects a <em>truststore</em>, which holds only public certificates. Unlike a
 * keystore password it guards integrity, not confidentiality - it stops someone appending a rogue
 * CA to the file, and reveals nothing if disclosed. It is still read from configuration rather than
 * frozen into the bytecode, mirroring how {@code ServerConfig} handles the server's keystore
 * password, so a deployment that ships a real pinned certificate can set its own.
 */
public final class TlsSupport {

    /** System property holding the truststore password. */
    public static final String PASSWORD_PROPERTY = "tetherless.truststore.password";

    /** Environment variable holding the truststore password. */
    public static final String PASSWORD_ENV = "TETHERLESS_TRUSTSTORE_PASSWORD";

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
            // Development default: the checked-in dev certificate uses the JDK's stock password.
            configured = "changeit";
        }
        return configured.toCharArray();
    }

    /** Loads the pinned development certificate as a truststore. */
    public static KeyStore loadTrustStore() throws Exception {
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
     * A TLS 1.3 context that trusts only the pinned development certificate.
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
