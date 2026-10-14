package com.feiniu.carmusic.data;

import android.os.Build;
import android.util.Base64;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Enables modern TLS on Android 4.x without weakening certificate validation. */
public final class TlsCompat {
    /** USERTrust ECC Certification Authority, required by the current FN Connect chain. */
    private static final String USERTRUST_ECC_ROOT_DER_BASE64 =
            "MIICjzCCAhWgAwIBAgIQXIuZxVqUxdJxVt7NiYDMJjAKBggqhkjOPQQDAzCBiDEL"
                    + "MAkGA1UEBhMCVVMxEzARBgNVBAgTCk5ldyBKZXJzZXkxFDASBgNVBAcTC0plcnNl"
                    + "eSBDaXR5MR4wHAYDVQQKExVUaGUgVVNFUlRSVVNUIE5ldHdvcmsxLjAsBgNVBAMT"
                    + "JVVTRVJUcnVzdCBFQ0MgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkwHhcNMTAwMjAx"
                    + "MDAwMDAwWhcNMzgwMTE4MjM1OTU5WjCBiDELMAkGA1UEBhMCVVMxEzARBgNVBAgT"
                    + "Ck5ldyBKZXJzZXkxFDASBgNVBAcTC0plcnNleSBDaXR5MR4wHAYDVQQKExVUaGUg"
                    + "VVNFUlRSVVNUIE5ldHdvcmsxLjAsBgNVBAMTJVVTRVJUcnVzdCBFQ0MgQ2VydGlm"
                    + "aWNhdGlvbiBBdXRob3JpdHkwdjAQBgcqhkjOPQIBBgUrgQQAIgNiAAQarFRaqflo"
                    + "I+d61SRvU8Za2EurxtW20eZzca7dnNYMYf3boIkDuAUU7FfO7l0/4iGzzvfUinng"
                    + "o4N+LZfQYcTxmdwlkWOrfzCjtHDix6EznPO/LlxTsV+zfTJ/ijTjeXmjQjBAMB0G"
                    + "A1UdDgQWBBQ64QmG1M8ZwpZ2dEl23OA1xmNjmjAOBgNVHQ8BAf8EBAMCAQYwDwYD"
                    + "VR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAwNoADBlAjA2Z6EWCNzklwBBHU6+4WMB"
                    + "zzuqQhFkoJ2UOQIReVx7Hfpkue4WQrO/isIJxOzksU0CMQDpKmFHjFJKS04YcPbW"
                    + "RNZu9YO6bVi9JNlWSOrvxKJGgYhqOkbRqZtNyWHa0V1Xahg=";
    /** Let's Encrypt's official self-signed ISRG Root X1 (SHA-256 96BCEC...DF08C6).
     * Source: https://letsencrypt.org/certs/isrgrootx1.pem
     * Current FN relays use YR1 -> Root YR -> ISRG Root X1, absent from Android 4.
     */
    private static final String ISRG_ROOT_X1_DER_BASE64 =
            "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAwTzELMAkGA1UEBhMC"
                    + "VVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2VhcmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JH"
                    + "IFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcG"
                    + "A1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY"
                    + "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygch77ct984kIxuPOZX"
                    + "oHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpm"
                    + "ZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6UA5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt1"
                    + "8jA8+o+u3dpjq+sWT8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH"
                    + "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UCB5iPNgiV5+I3lg02"
                    + "dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUvKBds0pjBqAlkd25HN7rOrFleaJ1/ctaJ"
                    + "xQZBKT5ZPt0m9STJEadao0xAH0ahmbWnOlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG"
                    + "6uBQE3xDk3SzynTnjh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw"
                    + "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CIrU7m2Ys6xt0nUW7/"
                    + "vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5"
                    + "tFnme7bl5AFzgAiIyBpY9umbbjANBgkqhkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1"
                    + "XIpu+ILlaS/V9lZLubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ"
                    + "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KKNFtY2PwByVS5uCbM"
                    + "iogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5ORAzI4JMPJ+GslWYHb4phowim57iaztX"
                    + "OoJwTdwJx4nLCgdNbOhdjsnvzqvHu7UrTkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nx"
                    + "e5AW0wdeRlN8NwdCjNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc"
                    + "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq4RgqsahDYVvTH9w7"
                    + "jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPAmRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/"
                    + "ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57demyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5X"
                    + "os1AnX5iItreGCc=";
    private static boolean initialized;
    private static SSLSocketFactory legacyFactory;
    private static Throwable initializationError;

    private TlsCompat() {
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return;
        try {
            Provider provider = Conscrypt.newProvider();
            Security.insertProviderAt(provider, 1);
            SSLContext context = SSLContext.getInstance("TLS", provider);
            context.init(null, new TrustManager[]{createCompositeTrustManager()}, null);
            legacyFactory = context.getSocketFactory();
        } catch (Throwable error) {
            initializationError = error;
        }
    }

    public static void configure(HttpURLConnection connection) throws IOException {
        if (!(connection instanceof HttpsURLConnection) || Build.VERSION.SDK_INT >= 21) return;
        initialize();
        if (legacyFactory == null) {
            String detail = initializationError == null ? "unknown"
                    : initializationError.getClass().getSimpleName();
            throw new IOException("Android 4 TLS 初始化失败：" + detail);
        }
        ((HttpsURLConnection) connection).setSSLSocketFactory(legacyFactory);
    }

    private static X509TrustManager createCompositeTrustManager() throws Exception {
        TrustManagerFactory systemFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        systemFactory.init((KeyStore) null);
        X509TrustManager system = findX509TrustManager(systemFactory.getTrustManagers());

        return new CompositeTrustManager(system, createBundledTrustManager());
    }

    private static X509TrustManager createBundledTrustManager() throws Exception {
        KeyStore bundledStore = KeyStore.getInstance(KeyStore.getDefaultType());
        bundledStore.load(null, null);
        addRoot(bundledStore, "usertrust-ecc-root", USERTRUST_ECC_ROOT_DER_BASE64);
        addRoot(bundledStore, "isrg-root-x1", ISRG_ROOT_X1_DER_BASE64);
        TrustManagerFactory bundledFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        bundledFactory.init(bundledStore);
        return findX509TrustManager(bundledFactory.getTrustManagers());
    }

    private static void addRoot(KeyStore store, String alias, String base64) throws Exception {
        byte[] der = Base64.decode(base64, Base64.DEFAULT);
        Certificate certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        store.setCertificateEntry(alias, certificate);
    }

    private static X509TrustManager findX509TrustManager(TrustManager[] managers)
            throws CertificateException {
        for (TrustManager manager : managers) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new CertificateException("X509TrustManager unavailable");
    }

    private static final class CompositeTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;

        CompositeTrustManager(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemError) {
                bundled.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] first = system.getAcceptedIssuers();
            X509Certificate[] second = bundled.getAcceptedIssuers();
            X509Certificate[] combined = new X509Certificate[first.length + second.length];
            System.arraycopy(first, 0, combined, 0, first.length);
            System.arraycopy(second, 0, combined, first.length, second.length);
            return combined;
        }
    }
}
