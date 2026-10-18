package com.feiniu.carmusic.data;

import android.content.Context;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Local transport checks: no real NAS or saved account is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MusicResourceTransportTest {
    private MockWebServer server;
    private FnApiClient api;
    private Context context;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("fn_session", 0).edit().clear().commit();
        Constructor<FnApiClient> constructor = FnApiClient.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        api = constructor.newInstance(context);
        server = new MockWebServer();
        server.start();
        api.session().saveLogin(server.url("/").toString().replaceAll("/$", ""),
                "local-test-token", "local-tester", "local");
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
        context.getSharedPreferences("fn_session", 0).edit().clear().commit();
    }

    @Test public void startPlaylistInitializationAndSegmentReuseOneCompletedConnection() throws Exception {
        byte[] playlist = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\n0.m4s\n"
                .getBytes(StandardCharsets.UTF_8);
        byte[] initialization = new byte[] {0, 1, 2, 3, 4};
        byte[] segment = new byte[] {5, 6, 7, 8, 9, 10};
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"status\":\"ready\"}}"));
        server.enqueue(new MockResponse().setBody(new Buffer().write(playlist)));
        server.enqueue(new MockResponse().setBody(new Buffer().write(initialization)));
        server.enqueue(new MockResponse().setBody(new Buffer().write(segment)));
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{}}"));
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{}}"));

        api.startTranscode("transport-song", "mp3", 192);
        assertArrayEquals(playlist, api.readMusicResource(api.transcodePlaylistUrl("transport-song"), 1024));
        assertArrayEquals(initialization,
                api.readMusicResource(server.url("/music/api/v1/track/hls/transport-song/init.mp4").toString(), 1024));
        assertArrayEquals(segment,
                api.readMusicResource(server.url("/music/api/v1/track/hls/transport-song/0.m4s").toString(), 1024));
        api.transcodeHeartbeat("transport-song", 2.0);
        api.quitTranscode("transport-song");

        String[] paths = {"/music/api/v1/track/transcode",
                "/music/api/v1/track/hls/transport-song/preset.m3u8",
                "/music/api/v1/track/hls/transport-song/init.mp4",
                "/music/api/v1/track/hls/transport-song/0.m4s",
                "/music/api/v1/track/transcode/heartbeat", "/music/api/v1/track/transcode/quit"};
        for (int i = 0; i < paths.length; i++) {
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(paths[i], request.getPath());
            assertEquals("music-token=local-test-token", request.getHeader("Cookie"));
            assertNotEquals("close", request.getHeader("Connection"));
            assertEquals("Completed responses must reuse the initial connection: " + paths[i],
                    i, request.getSequenceNumber());
        }
    }

    @Test public void missingOriginalAudioRetainsHttpStatusAndLoginWithoutRetrying() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        File output = new File(context.getCacheDir(), "missing-original-transport.track");
        if (output.exists()) assertTrue(output.delete());
        try {
            api.downloadTrack("missing-song", output);
            fail("A missing original must report its HTTP status");
        } catch (ApiException error) {
            assertEquals(404, error.getStatusCode());
            assertTrue(error.getMessage().contains("404"));
            assertFalse(error.getMessage().contains("local-test-token"));
        }
        assertEquals("local-test-token", api.session().getToken());
        assertEquals(1, server.getRequestCount());
        assertEquals("/music/api/v1/track/stream?guid=missing-song", server.takeRequest().getPath());
        assertFalse(output.exists() && output.length() > 0L);
    }

    @Test public void progressingDownloadCanOutlastIdleTimeoutWithinItsOverallBudget() throws Exception {
        byte[] content = new byte[100];
        server.enqueue(new MockResponse().setBody(new Buffer().write(content))
                .throttleBody(10, 100, TimeUnit.MILLISECONDS));
        assertArrayEquals(content, api.readMusicResource(api.transcodePlaylistUrl("slow-progress"),
                1024, 400, 2500));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void forbiddenHlsResourceReportsPermissionAndRetainsLogin() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        try {
            api.readMusicResource(api.transcodePlaylistUrl("forbidden-song"), 1024);
            fail("A forbidden resource cannot be treated as playable bytes");
        } catch (ApiException error) {
            assertEquals(403, error.getStatusCode());
            assertTrue("Permission failure should be distinguished from missing segments",
                    error.getMessage().contains("权限"));
        }
        assertEquals("local-test-token", api.session().getToken());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void unauthorizedHlsResourceClearsExpiredLogin() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        try {
            api.readMusicResource(api.transcodePlaylistUrl("expired-song"), 1024);
            fail("An expired login must be reported");
        } catch (ApiException error) {
            assertEquals(401, error.getStatusCode());
            assertTrue(error.getMessage().contains("登录"));
        }
        assertEquals("", api.session().getToken());
        assertEquals(1, server.getRequestCount());
    }

    @Test public void crossOriginResourceIsRejectedBeforeSendingAnyRequest() throws Exception {
        MockWebServer other = new MockWebServer();
        other.start();
        try {
            try {
                api.readMusicResource(other.url("/private-audio").toString(), 1024);
                fail("A playlist cannot export music cookies to another origin");
            } catch (ApiException error) {
                assertTrue(error.getMessage().contains("其他服务器"));
            }
            assertEquals(0, other.getRequestCount());
            assertEquals(0, server.getRequestCount());
            assertEquals("local-test-token", api.session().getToken());
        } finally { other.shutdown(); }
    }

    @Test public void resourceRedirectDoesNotExportCookieToItsTarget() throws Exception {
        MockWebServer other = new MockWebServer();
        other.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", other.url("/redirect-target")));
            try {
                api.readMusicResource(api.transcodePlaylistUrl("redirected-song"), 1024);
                fail("Resource redirects must not export authentication");
            } catch (ApiException error) {
                assertEquals(302, error.getStatusCode());
            }
            assertEquals("music-token=local-test-token", server.takeRequest().getHeader("Cookie"));
            assertEquals(0, other.getRequestCount());
            assertEquals("local-test-token", api.session().getToken());
        } finally { other.shutdown(); }
    }
}
