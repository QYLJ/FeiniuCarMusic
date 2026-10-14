package com.feiniu.carmusic.update;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AlistDownloadResolverTest {
    private MockWebServer server;

    @Before public void setUp() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void restrictedGuestGetsFreshSignedLinkWithoutCredentials() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"data\":{\"sign\":\"fresh-sign:0\",\"is_dir\":false}}"));
        String direct = server.url("/d/feiniu-updates/update.json").toString();
        String signed = AlistDownloadResolver.resolve(direct);
        assertEquals(direct + "?sign=fresh-sign%3A0", signed);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/api/fs/get", request.getPath());
        assertEquals("POST", request.getMethod());
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("/update.json", body.getString("path"));
        assertEquals("", body.getString("password"));
        assertTrue(body.getBoolean("refresh"));
        assertNull(request.getHeader("Cookie"));
        assertNull(request.getHeader("Authorization"));
    }

    @Test public void rootGuestFallsBackToFullStoragePath() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":500,\"message\":\"file not found\"}"));
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"data\":{\"sign\":\"apk-sign:0\"}}"));
        String direct = server.url("/d/feiniu-updates/app.apk").toString();
        assertTrue(AlistDownloadResolver.resolve(direct).endsWith("?sign=apk-sign%3A0"));
        server.takeRequest();
        assertEquals("/feiniu-updates/app.apk", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("path"));
    }

    @Test public void nestedChineseDirectoryUsesGuestRelativePathAndPreservesEncodedUrl() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"data\":{\"sign\":\"chinese-path-sign:0\"}}"));
        String direct = server.url("/d/20T%E5%A4%A7%E7%A1%AC%E7%9B%98/QY_net/feiniu-updates/update.json").toString();
        assertEquals(direct + "?sign=chinese-path-sign%3A0", AlistDownloadResolver.resolve(direct));
        assertEquals("/update.json", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("path"));
    }

    @Test public void nestedRootGuestGetsDecodedFullPathWithoutLosingPlusOrSpaces() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":500,\"message\":\"not found\"}"));
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"data\":{\"sign\":\"apk-sign:0\"}}"));
        String direct = server.url("/d/20T%E5%A4%A7%E7%A1%AC%E7%9B%98/QY_net/feiniu-updates/a+b%20c.apk").toString();
        AlistDownloadResolver.resolve(direct);
        assertEquals("/a+b c.apk", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("path"));
        assertEquals("/20T大硬盘/QY_net/feiniu-updates/a+b c.apk",
                new JSONObject(server.takeRequest().getBody().readUtf8()).getString("path"));
    }

    @Test public void disabledGuestIsReportedWithoutRequestingLogin() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":401,\"message\":\"Guest user is disabled, login please\"}"));
        try { AlistDownloadResolver.resolve(server.url("/d/feiniu-updates/update.json").toString()); fail("disabled guest must fail"); }
        catch (IllegalStateException error) { assertTrue(error.getMessage().contains("访客访问尚未启用")); }
        assertEquals(1, server.getRequestCount());
    }

    @Test public void existingSignedOrNonAlistLinkIsPreserved() throws Exception {
        String signed = server.url("/d/feiniu-updates/update.json?sign=existing%3A0").toString();
        assertEquals(signed, AlistDownloadResolver.resolve(signed));
        String plain = server.url("/releases/update.json").toString();
        assertEquals(plain, AlistDownloadResolver.resolve(plain));
        assertEquals(0, server.getRequestCount());
    }
}
