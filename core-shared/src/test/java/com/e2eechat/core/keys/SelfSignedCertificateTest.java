package com.e2eechat.core.keys;

import com.e2eechat.core.identity.PeerId;

import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The container certificate written into the identity keystore.
 *
 * <p>Hand-encoded DER is the kind of code that fails silently in ways that only surface much
 * later - a keystore that will not open, or an identity that comes back with the wrong key - so
 * every field that matters is asserted here rather than trusted.
 */
public class SelfSignedCertificateTest {

    private static KeyPair keyPair;

    @BeforeClass
    public static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    public void theEncodingParsesAsAnX509Certificate() throws Exception {
        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

        assertEquals("X.509", certificate.getType());
        assertEquals(1, certificate.getVersion());
    }

    /** If this key were not the identity key, every peer id the client derives would be wrong. */
    @Test
    public void itCarriesExactlyTheKeyItWasGivenFor() throws Exception {
        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

        assertArrayEquals(keyPair.getPublic().getEncoded(),
                certificate.getPublicKey().getEncoded());
        assertEquals(PeerId.of(keyPair.getPublic()), PeerId.of(certificate.getPublicKey()));
    }

    @Test
    public void itIsSignedByItsOwnKey() throws Exception {
        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

        // Throws if the signature does not verify, which is what makes the DER encoding of the
        // signed half provably correct: the bytes signed are the bytes a parser reads back.
        certificate.verify(keyPair.getPublic());
        assertEquals("SHA256withRSA", certificate.getSigAlgName());
    }

    @Test
    public void theSubjectIsTheIssuerAndCarriesTheNameGiven() throws Exception {
        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

        assertEquals(certificate.getIssuerX500Principal(), certificate.getSubjectX500Principal());
        assertEquals("CN=Tetherless Identity", certificate.getSubjectX500Principal().getName());
    }

    @Test
    public void itIsValidNowAndForYearsYet() throws Exception {
        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

        certificate.checkValidity();
        long fiveYears = 5L * 365 * 24 * 60 * 60 * 1000;
        certificate.checkValidity(new Date(System.currentTimeMillis() + fiveYears));
        assertTrue(certificate.getNotAfter().after(certificate.getNotBefore()));
    }

    /** Serial numbers must be positive; a zero or negative serial is malformed. */
    @Test
    public void serialNumbersArePositiveAndDiffer() throws Exception {
        X509Certificate first = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");
        X509Certificate second = SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

        assertTrue(first.getSerialNumber().signum() > 0);
        assertTrue(second.getSerialNumber().signum() > 0);
        assertTrue("serials should not repeat",
                !first.getSerialNumber().equals(second.getSerialNumber()));
    }

    /**
     * A name long enough to push the DER length past 127 bytes, which is where the encoder has to
     * switch to the long form. Getting that wrong produces a certificate nothing can parse.
     */
    @Test
    public void aLongNameStillEncodes() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            name.append("long-name-segment-");
        }

        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, name.toString());

        certificate.verify(keyPair.getPublic());
        assertEquals("CN=" + name, certificate.getSubjectX500Principal().getName());
    }

    /**
     * A locale whose digits are not Latin must not reach the encoding.
     *
     * <p>{@code %02d} renders Arabic-Indic digits under an Arabic locale, which inside a DER
     * UTCTime produces a certificate nothing can parse - so identity creation would fail outright
     * for those users, and only for those users.
     */
    @Test
    public void aNonLatinDefaultLocaleDoesNotCorruptTheEncoding() throws Exception {
        Locale original = Locale.getDefault();
        try {
            for (String tag : new String[]{"ar-SA-u-nu-arab", "bn-IN-u-nu-beng"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));

                X509Certificate certificate =
                        SelfSignedCertificate.generate(keyPair, "Tetherless Identity");

                certificate.verify(keyPair.getPublic());
                certificate.checkValidity();
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void nonAsciiNamesSurvive() throws Exception {
        X509Certificate certificate = SelfSignedCertificate.generate(keyPair, "Ünïcode Idéntity");

        certificate.verify(keyPair.getPublic());
        assertTrue(certificate.getSubjectX500Principal().getName().contains("Idéntity"));
    }
}
