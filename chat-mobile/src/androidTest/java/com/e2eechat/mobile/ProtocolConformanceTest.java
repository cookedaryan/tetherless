package com.e2eechat.mobile;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.e2eechat.core.protocol.ProtocolConformance;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Runs the frozen protocol and key-derivation vectors on a real Android runtime.
 *
 * <p>This is the other half of the guard: {@code ProtocolVectorsTest} and {@code CryptoVectorsTest}
 * assert the same values on the JVM, and both call into {@link ProtocolConformance} so the two
 * platforms cannot drift apart in the checks themselves.
 *
 * <p>It matters because Android's crypto comes from Conscrypt rather than the JDK providers, and
 * the ways the two differ - notably whether a short Diffie-Hellman secret is returned padded - do
 * not show up in a desktop-only test suite. Left unguarded, a mismatch surfaces as a handshake that
 * fails roughly one time in 256, which is close to impossible to diagnose from a bug report.
 *
 * <p>Must run as an instrumented test ({@code connectedAndroidTest}), not a unit test: a JVM-hosted
 * unit test would exercise the desktop providers again and prove nothing.
 */
@RunWith(AndroidJUnit4.class)
public class ProtocolConformanceTest {

    @Test
    public void androidAgreesWithTheFrozenVectors() {
        List<String> failures = ProtocolConformance.run();

        StringBuilder report = new StringBuilder();
        report.append("Android does not match the committed protocol vectors.\n")
              .append("Either this platform encodes or derives differently from the JVM, or the\n")
              .append("format changed without protocolVersion being bumped.\n\n");
        for (String failure : failures) {
            report.append("  - ").append(failure).append('\n');
        }

        assertTrue(report.toString(), failures.isEmpty());
    }
}
