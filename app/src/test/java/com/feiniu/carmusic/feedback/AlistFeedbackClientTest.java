package com.feiniu.carmusic.feedback;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.net.URLDecoder;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AlistFeedbackClientTest {
    private MockWebServer server;

    @Before public void setUp() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void encryptedEnvelopeUploadsWithoutCredentials() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"message\":\"success\",\"data\":null}"));
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        String reportId = "20261017-120000-abcdef123456";
        AlistFeedbackClient.UploadReceipt receipt = new AlistFeedbackClient(
                server.url("/api/fs/put").toString(), "/feedback inbox").upload(payload, reportId);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("PUT", request.getMethod());
        assertEquals("/api/fs/put", request.getPath());
        assertEquals("/feedback inbox/FeiniuCarMusic-feedback-" + reportId + ".fnfeedback",
                URLDecoder.decode(request.getHeader("File-Path"), "UTF-8"));
        assertNull(request.getHeader("Authorization"));
        assertArrayEquals(payload, request.getBody().readByteArray());
        assertEquals(payload.length, receipt.bytes);
        assertEquals(reportId, receipt.reportId);
        assertEquals(64, receipt.sha256.length());
    }

    @Test public void alistErrorIsReportedWithoutLeakingBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"code\":403,\"message\":\"permission denied\",\"secret\":\"do-not-show\"}"));
        try {
            new AlistFeedbackClient(server.url("/api/fs/put").toString(), "/feedback")
                    .upload(new byte[] {1}, "20261017-120000-abcdef123456");
            fail("permission error expected");
        } catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("HTTP 403"));
            assertTrue(error.getMessage().contains("permission denied"));
            assertTrue(!error.getMessage().contains("do-not-show"));
        }
    }
}
