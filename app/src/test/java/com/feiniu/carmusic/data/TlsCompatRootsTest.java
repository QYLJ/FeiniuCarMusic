package com.feiniu.carmusic.data;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TlsCompatRootsTest {
    @Test public void legacyRootsIncludeAuthenticIsrgX1AndExistingUsertrust() throws Exception {
        X509TrustManager manager = bundledManager();
        assertEquals(2, manager.getAcceptedIssuers().length);
        boolean foundIsrg = false, foundUsertrust = false;
        for (X509Certificate root : manager.getAcceptedIssuers()) {
            root.verify(root.getPublicKey());
            if (root.getSubjectX500Principal().getName().contains("ISRG Root X1")) {
                assertEquals("96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6", fingerprint(root));
                manager.checkServerTrusted(new X509Certificate[] {root}, "RSA");
                foundIsrg = true;
            }
            if (root.getSubjectX500Principal().getName().contains("USERTrust ECC")) foundUsertrust = true;
        }
        assertTrue(foundIsrg);
        assertTrue(foundUsertrust);
    }

    @Test public void addingIsrgDoesNotTrustUnrelatedSelfSignedCertificate() throws Exception {
        InputStream input = getClass().getResourceAsStream("/untrusted-test-ca.pem");
        assertNotNull(input);
        X509Certificate certificate;
        try { certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input); }
        finally { input.close(); }
        try {
            bundledManager().checkServerTrusted(new X509Certificate[] {certificate}, "RSA");
            fail("an unknown certificate must still be rejected");
        } catch (CertificateException expected) { }
    }

    private X509TrustManager bundledManager() throws Exception {
        Method method = TlsCompat.class.getDeclaredMethod("createBundledTrustManager");
        method.setAccessible(true);
        return (X509TrustManager) method.invoke(null);
    }

    private String fingerprint(X509Certificate certificate) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
}
