package com.e2eechat.server;

import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Which interface the metrics endpoint listens on.
 *
 * <p>{@code /metrics} is unauthenticated plain HTTP, and it used to be bound to the wildcard
 * address - so a relay on a public host with nothing in front of it published its client count,
 * routed-message totals and rejection counters to anyone who connected. That is a live read on who
 * is using the service and how heavily, offered by the one component in this design that is meant
 * to learn as little as possible.
 */
public class MetricsBindingTest {

    private MetricsServer metrics;

    @After
    public void tearDown() {
        if (metrics != null) {
            metrics.stop();
        }
    }

    /** An unconfigured relay binds loopback, not everything. */
    @Test
    public void theDefaultIsLoopback() {
        assertEquals(MetricsServer.DEFAULT_HOST, new ServerConfig().getMetricsHost());
    }

    /** An empty or absent setting must not fall back to the wildcard address. */
    @Test
    public void anEmptyHostFallsBackToLoopbackRatherThanEverything() throws Exception {
        metrics = new MetricsServer(0, "");
        metrics.start();

        assertTrue("loopback must serve it", reachable(InetAddress.getLoopbackAddress()));
        assertNotReachableOffLoopback();
    }

    @Test
    public void theEndpointIsNotServedOnARoutableInterface() throws Exception {
        metrics = new MetricsServer(0, MetricsServer.DEFAULT_HOST);
        metrics.start();

        assertTrue("loopback must still serve it", reachable(InetAddress.getLoopbackAddress()));
        assertNotReachableOffLoopback();
    }

    /**
     * Tries every non-loopback address this host has. A machine with none is not evidence either
     * way, so the test says so rather than passing quietly.
     */
    private void assertNotReachableOffLoopback() throws Exception {
        int tried = 0;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface nic : Collections.list(interfaces)) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
                    continue;
                }
                tried++;
                if (reachable(address)) {
                    fail("metrics answered on " + address.getHostAddress()
                            + "; the endpoint is unauthenticated and must stay on loopback");
                }
            }
        }
        org.junit.Assume.assumeTrue(
                "no routable interface on this host to test against", tried > 0);
    }

    private boolean reachable(InetAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, metrics.getPort()), 1000);
            return true;
        } catch (Exception refused) {
            return false;
        }
    }
}
