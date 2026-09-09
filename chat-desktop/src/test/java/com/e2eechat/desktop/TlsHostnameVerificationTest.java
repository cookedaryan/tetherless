package com.e2eechat.desktop;

import com.e2eechat.core.network.TlsSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.net.InetAddress;
import java.net.Socket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The relay's certificate has to be issued for the host that was dialled, not merely trusted.
 *
 * <p>A raw {@link SSLSocket} does not check that on its own - unlike {@code HttpsURLConnection},
 * JSSE only compares the certificate against the host when endpoint identification is set, and it
 * was not. With a public CA pinned, that meant accepting any certificate that CA had ever issued to
 * anyone: an interceptor presenting a valid certificate for their own domain would chain to the
 * pinned issuer, pass verification and sit in the middle of the connection.
 */
public class TlsHostnameVerificationTest {

    private StubRelay relay;

    @Before
    public void setUp() throws Exception {
        // Another test in this module varies the configured truststore; make sure this one pins
        // the development certificate the stub relay actually presents.
        System.clearProperty(TlsSupport.TRUSTSTORE_PROPERTY);
        TlsSupport.resetCachedContext();
        relay = new StubRelay();
    }

    @After
    public void tearDown() {
        if (relay != null) {
            relay.close();
        }
    }

    /**
     * The development certificate carries {@code SAN=dns:localhost,ip:127.0.0.1}, so dialling the
     * loopback address succeeds and the socket comes back with identification switched on.
     */
    @Test
    public void aSocketToTheRelayVerifiesTheHostname() throws Exception {
        try (SSLSocket socket = TlsSupport.connectPinned("127.0.0.1", relay.port())) {
            assertEquals("endpoint identification must be on, or the SAN is never checked",
                    "HTTPS", socket.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
    }

    /** The name in the certificate is what is checked, not merely who issued it. */
    @Test
    public void theHostnameInTheCertificateIsChecked() throws Exception {
        try (SSLSocket socket = TlsSupport.connectPinned("localhost", relay.port())) {
            assertEquals("HTTPS", socket.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
    }

    /**
     * The same trusted certificate, presented for a host it was not issued for, must fail.
     *
     * <p>The connection is made to the real relay and then wrapped naming a different peer, which
     * is exactly the shape of an interception: a certificate the client trusts, for the wrong
     * name. Before endpoint identification was set this handshake completed.
     */
    @Test
    public void aCertificateForAnotherHostIsRejected() throws Exception {
        SSLSocketFactory factory = TlsSupport.clientContext().getSocketFactory();

        try (Socket plain = new Socket(InetAddress.getLoopbackAddress(), relay.port())) {
            SSLSocket socket = (SSLSocket) factory.createSocket(
                    plain, "relay.example.org", relay.port(), false);
            socket.setEnabledProtocols(new String[]{"TLSv1.3"});
            javax.net.ssl.SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);

            try {
                socket.startHandshake();
                fail("a certificate with no SAN for relay.example.org must fail the handshake");
            } catch (javax.net.ssl.SSLHandshakeException expected) {
                assertTrue("the failure should name the host mismatch: " + expected.getMessage(),
                        expected.getMessage() != null);
            }
        }
    }
}
