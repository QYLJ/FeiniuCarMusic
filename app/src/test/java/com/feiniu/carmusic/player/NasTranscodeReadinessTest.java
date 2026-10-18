package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.data.ApiException;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import okhttp3.mockwebserver.Dispatcher;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class NasTranscodeReadinessTest {
    private MockWebServer server;
    private FnApiClient api;
    private Context context;
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger quits = new AtomicInteger();
    private final CountDownLatch missingRead = new CountDownLatch(1);

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Field singleton = FnApiClient.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, null);
        server = new MockWebServer(); server.start();
        api = FnApiClient.get(context);
        api.session().saveLogin(server.url("/").toString().replaceAll("/$", ""), "test-token", "tester", "local");
    }
    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void playlistCanAppearAfterMoreThanThree404Responses() throws Exception {
        assertLateResource("preset.m3u8", 404);
    }
    @Test public void initializationCanAppearAfterMoreThanThree404Responses() throws Exception {
        assertLateResource("mp3-init.mp4", 404);
    }
    @Test public void segmentCanAppearAfterMoreThanThree404Responses() throws Exception {
        assertLateResource("mp3-0.m4s", 404);
    }
    @Test public void temporaryServerFailureCanRecoverWithoutRestartingTranscode() throws Exception {
        assertLateResource("preset.m3u8", 503);
    }

    private void assertLateResource(String missing, int status) throws Exception {
        serve(missing, 3, status, false);
        File target = new File(context.getCacheDir(), "readiness-" + missing + ".track");
        AudioFile audio = new NasTranscodeDownloader(api, 5000).download("ready-song", "mp3", target);
        assertTrue(audio.file.length() > 1000);
        assertEquals(4, reads.get());
        assertEquals(1, quits.get());
        assertEquals("test-token", api.session().getToken());
    }

    @Test public void permanent404IsBoundedAndIdentifiesTheMissingResource() throws Exception {
        serve("preset.m3u8", Integer.MAX_VALUE, 404, false);
        long started = System.nanoTime();
        try {
            new NasTranscodeDownloader(api, 900).download("missing-song", "mp3",
                    new File(context.getCacheDir(), "permanent-missing.track"));
            fail("A permanently missing resource must fail");
        } catch (ApiException error) {
            assertEquals(404, error.getStatusCode());
            assertTrue(error.getMessage().contains("转码播放列表"));
            assertTrue(error.getMessage().contains("等待超时"));
            assertTrue(error.getMessage().contains("HTTP 404"));
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
        assertEquals(1, quits.get());
        assertEquals("test-token", api.session().getToken());
    }

    @Test public void forbiddenResourceFailsOnceWithoutSpendingReadinessBudget() throws Exception {
        serve("preset.m3u8", Integer.MAX_VALUE, 403, false);
        try {
            new NasTranscodeDownloader(api, 5000).download("forbidden-song", "mp3",
                    new File(context.getCacheDir(), "forbidden-readiness.track"));
            fail();
        } catch (ApiException error) {
            assertEquals(403, error.getStatusCode());
            assertTrue(error.getMessage().contains("权限"));
        }
        assertEquals(1, reads.get());
        assertEquals(1, quits.get());
        assertEquals("test-token", api.session().getToken());
    }

    @Test public void cancellingWhileWaitingReleasesSessionAndLeavesNoPlayableCache() throws Exception {
        serve("preset.m3u8", Integer.MAX_VALUE, 404, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch ended = new CountDownLatch(1);
        Track track = new Track(); track.guid = "cancel-readiness";
        try {
            Future<?> future = executor.submit(() -> {
                try { new TrackFileCache(context).getOrDownload(track, "mp3"); fail(); }
                catch (Exception expected) { assertTrue(expected instanceof java.io.InterruptedIOException || expected instanceof InterruptedException); }
                finally { ended.countDown(); }
            });
            assertTrue(missingRead.await(2, TimeUnit.SECONDS));
            future.cancel(true);
            assertTrue(ended.await(2, TimeUnit.SECONDS));
            assertEquals(1, quits.get());
            File[] markers = new File(context.getCacheDir(), "tracks").listFiles((dir, name) -> name.endsWith(".complete"));
            assertNotNull(markers);
            assertEquals(0, markers.length);
        } finally { executor.shutdownNow(); }
    }

    @Test public void socketTimeoutCanRetryWithinTheRemainingBudget() throws Exception {
        serve("preset.m3u8", 1, 200, true);
        AudioFile audio = new NasTranscodeDownloader(api, 6500).download("timeout-song", "mp3",
                new File(context.getCacheDir(), "timeout-readiness.track"));
        assertTrue(audio.file.length() > 1000);
        assertEquals(2, reads.get());
        assertEquals(1, quits.get());
    }

    @Test public void emptyLivePlaylistCannotWaitForeverForFirstAudio() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().endsWith("/quit")) { quits.incrementAndGet(); return json(); }
                if (request.getPath().endsWith(".m3u8")) return new MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:2\n");
                return json();
            }
        });
        long started = System.nanoTime();
        try {
            new NasTranscodeDownloader(api, 300).download("empty-song", "mp3",
                    new File(context.getCacheDir(), "empty-readiness.track"));
            fail();
        } catch (ApiException expected) { assertTrue(expected.getMessage().contains("转码播放列表")); }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
        assertEquals(1, quits.get());
    }

    private void serve(final String delayed, final int failures, final int status, final boolean slow) throws Exception {
        final byte[] init = resource("mp3-init.mp4");
        final byte[] segment = resource("mp3-0.m4s");
        final String playlist = "#EXTM3U\n#EXT-X-MAP:URI=\"mp3-init.mp4\"\n#EXTINF:0.25,\nmp3-0.m4s\n#EXT-X-ENDLIST\n";
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.endsWith("/quit")) { quits.incrementAndGet(); return json(); }
                if (path.endsWith(delayed) && reads.incrementAndGet() <= failures) {
                    missingRead.countDown();
                    MockResponse response = new MockResponse().setResponseCode(status).setBody(slow ? playlist : "pending");
                    return slow ? response.setHeadersDelay(4500, TimeUnit.MILLISECONDS) : response;
                }
                if (path.endsWith(".m3u8")) return new MockResponse().setBody(playlist);
                if (path.endsWith(".mp4")) return new MockResponse().setBody(new Buffer().write(init));
                if (path.endsWith(".m4s")) return new MockResponse().setBody(new Buffer().write(segment));
                return json();
            }
        });
    }

    @Test public void completeFileBecomesPlayableBeforeSlowQuitReturns() throws Exception {
        final byte[] init = resource("mp3-init.mp4"), segment = resource("mp3-0.m4s");
        CountDownLatch ready = new CountDownLatch(1), quitting = new CountDownLatch(1), releaseQuit = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getPath();
                if (path.endsWith("/quit")) { quitting.countDown(); releaseQuit.await(5, TimeUnit.SECONDS); return json(); }
                if (path.endsWith(".m3u8")) return new MockResponse().setBody("#EXTM3U\n#EXT-X-MAP:URI=\"mp3-init.mp4\"\n#EXTINF:0.25,\nmp3-0.m4s\n#EXT-X-ENDLIST\n");
                if (path.endsWith(".mp4")) return new MockResponse().setBody(new Buffer().write(init));
                if (path.endsWith(".m4s")) return new MockResponse().setBody(new Buffer().write(segment));
                return json();
            }
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Track track = new Track(); track.guid = "complete-before-quit";
        final AudioFile[] playable = {null};
        try {
            Future<AudioFile> result = worker.submit(() -> new TrackFileCache(context).getOrDownload(track, "mp3", 192,
                    null, null, audio -> { playable[0] = audio; ready.countDown(); }));
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertTrue(quitting.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertTrue(playable[0].file.length() > 1000);
            assertFalse(new File(playable[0].file.getPath() + ".complete").exists());
            releaseQuit.countDown();
            assertEquals(playable[0].file, result.get(2, TimeUnit.SECONDS).file);
            assertTrue(new File(playable[0].file.getPath() + ".complete").isFile());
        } finally { releaseQuit.countDown(); worker.shutdownNow(); }
    }
    private MockResponse json() { return new MockResponse().setBody("{\"code\":0,\"data\":{\"status\":\"ready\"}}"); }
    private byte[] resource(String name) throws Exception {
        InputStream input = getClass().getResourceAsStream("/hls-audio/" + name);
        assertNotNull(input);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        } finally { input.close(); }
    }
}
