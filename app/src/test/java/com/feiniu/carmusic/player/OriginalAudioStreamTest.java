package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.data.FnApiClient;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.net.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class OriginalAudioStreamTest {
    private MockWebServer nas;
    private OriginalAudioStream stream;
    private FnApiClient api;

    @Before public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("fn_session", 0).edit().clear().commit();
        Constructor<FnApiClient> constructor = FnApiClient.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        api = constructor.newInstance(context);
        java.lang.reflect.Field singleton = FnApiClient.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, api);
        nas = new MockWebServer(); nas.start();
        api.session().saveLogin(nas.url("/").toString().replaceAll("/$", ""), "test-token", "tester", "127.0.0.1");
        stream = new OriginalAudioStream(api, "source-song");
    }
    @After public void tearDown() throws Exception { stream.close(); nas.shutdown(); }

    @Test public void firstAudioArrivesBeforeFullDownloadAndNasCredentialsStayOutOfPlayerUrl() throws Exception {
        byte[] bytes = new byte[256 * 1024];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        nas.enqueue(new MockResponse().setHeader("Content-Type", "audio/mpeg")
                .setBody(new Buffer().write(bytes)).throttleBody(16 * 1024, 500, TimeUnit.MILLISECONDS));
        HttpURLConnection player = (HttpURLConnection) new URL(stream.url()).openConnection();
        player.setReadTimeout(3000);
        try {
            assertEquals(200, player.getResponseCode());
            assertEquals(bytes.length, player.getContentLength());
            InputStream audio = player.getInputStream();
            byte[] first = new byte[1024];
            int count = audio.read(first);
            assertTrue(count > 0);
            for (int i = 0; i < count; i++) assertEquals(bytes[i], first[i]);
            assertFalse(stream.url().contains("test-token"));
            assertEquals("127.0.0.1", new URL(stream.url()).getHost());
            RecordedRequest request = nas.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/music/api/v1/track/stream?guid=source-song", request.getPath());
            assertEquals("music-token=test-token", request.getHeader("Cookie"));
        } finally { player.disconnect(); }
    }

    @Test public void seekRangeAndActualLengthAreForwardedWithoutInventedBytes() throws Exception {
        nas.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/8")
                .setHeader("Accept-Ranges", "bytes").setBody(new Buffer().write(new byte[] {4, 5, 6, 7})));
        HttpURLConnection player = (HttpURLConnection) new URL(stream.url()).openConnection();
        player.setReadTimeout(3000); player.setRequestProperty("Range", "bytes=4-7");
        try {
            assertEquals(206, player.getResponseCode());
            assertEquals("bytes 4-7/8", player.getHeaderField("Content-Range"));
            assertEquals(4, player.getContentLength());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = player.getInputStream(); int value;
            while ((value = input.read()) != -1) bytes.write(value);
            assertArrayEquals(new byte[] {4, 5, 6, 7}, bytes.toByteArray());
            assertEquals("bytes=4-7", nas.takeRequest(1, TimeUnit.SECONDS).getHeader("Range"));
        } finally { player.disconnect(); }
    }

    @Test public void completedLocalFileSupportsOfflineSeekAndRejectsInvalidRange() throws Exception {
        File fixture = File.createTempFile("local-track", ".track");
        try (FileOutputStream output = new FileOutputStream(fixture)) { output.write(new byte[] {1,2,3,4,5}); }
        try (OriginalAudioStream local = new OriginalAudioStream(new AudioFile(fixture, "aac", 44100, 16, 2, true))) {
            HttpURLConnection request = (HttpURLConnection) new URL(local.url()).openConnection();
            request.setRequestProperty("Range", "bytes=2-3");
            assertEquals(206, request.getResponseCode()); assertEquals("audio/aac", request.getContentType());
            assertEquals("bytes 2-3/5", request.getHeaderField("Content-Range"));
            try (InputStream body = request.getInputStream()) {
                assertEquals(3, body.read()); assertEquals(4, body.read()); assertEquals(-1, body.read());
            } finally { request.disconnect(); }
            request = (HttpURLConnection) new URL(local.url()).openConnection();
            request.setRequestProperty("Range", "bytes=8-");
            try { assertEquals(416, request.getResponseCode()); } finally { request.disconnect(); }
            assertEquals(0, nas.getRequestCount());
        } finally { fixture.delete(); }
    }

    @Test public void wrongPathCannotReadNasAndCloseReleasesListeningSocket() throws Exception {
        URL audioUrl = new URL(stream.url());
        HttpURLConnection wrong = (HttpURLConnection) new URL(audioUrl, "/other-song").openConnection();
        try { assertEquals(404, wrong.getResponseCode()); }
        finally { wrong.disconnect(); }
        assertEquals(0, nas.getRequestCount());
        stream.close();
        try {
            Socket client = new Socket();
            try { client.connect(new InetSocketAddress("127.0.0.1", audioUrl.getPort()), 1000); fail("stream socket must be closed"); }
            finally { client.close(); }
        } catch (IOException expected) { }
    }

    @Test public void slowDisconnectNeverBlocksTheCallerOrRunsOnTheCallingThread() throws Exception {
        final Thread caller = Thread.currentThread();
        final java.util.concurrent.CountDownLatch disconnecting = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Thread> closedOn = new java.util.concurrent.atomic.AtomicReference<>();
        HttpURLConnection connection = new HttpURLConnection(new URL("http://127.0.0.1/unused")) {
            @Override public void disconnect() {
                closedOn.set(Thread.currentThread()); disconnecting.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        java.lang.reflect.Field upstreams = OriginalAudioStream.class.getDeclaredField("upstreams"); upstreams.setAccessible(true);
        ((java.util.Set<HttpURLConnection>) upstreams.get(stream)).add(connection);
        try {
            long started = System.nanoTime();
            stream.close();
            assertTrue("Closing must not wait for response draining", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            assertTrue(disconnecting.await(2, TimeUnit.SECONDS));
            assertNotSame(caller, closedOn.get());
        } finally { release.countDown(); }
    }

    @Test public void completedOriginalStreamIsReusableOfflineButPartialBytesAreNotPublished() throws Exception {
        TrackFileCache cache = new TrackFileCache(RuntimeEnvironment.getApplication());
        com.feiniu.carmusic.model.Track track = new com.feiniu.carmusic.model.Track();
        track.guid = "cached-original"; track.codec = "mp3"; track.sampleRate = 44100;
        byte[] bytes = new byte[] {1, 2, 3, 4, 5};
        TrackFileCache.StreamCache partial = cache.cacheOriginalStream(track);
        try { partial.write(bytes, 2); partial.finish(5); }
        finally { partial.close(); }
        assertNull(cache.cachedOriginal(track));
        stream.close();
        stream = new OriginalAudioStream(api, track, cache);
        nas.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-4/5")
                .setBody(new Buffer().write(bytes)));
        HttpURLConnection player = (HttpURLConnection) new URL(stream.url()).openConnection();
        player.setRequestProperty("Range", "bytes=0-");
        try {
            InputStream input = player.getInputStream();
            while (input.read() != -1) { }
        } finally { player.disconnect(); }
        for (int i = 0; i < 200 && cache.cachedOriginal(track) == null; i++) Thread.sleep(10);
        assertNotNull(cache.cachedOriginal(track));
        assertEquals(bytes.length, cache.getOrDownload(track, "").file.length());
        assertEquals(1, nas.getRequestCount());
    }

    @Test public void tailRangeResponseIsNotPublishedAsACompleteCachedSong() throws Exception {
        TrackFileCache cache = new TrackFileCache(RuntimeEnvironment.getApplication());
        com.feiniu.carmusic.model.Track track = new com.feiniu.carmusic.model.Track();
        track.guid = "partial-range-song"; track.codec = "mp3";
        stream.close();
        stream = new OriginalAudioStream(api, track, cache);
        nas.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/8")
                .setBody(new Buffer().write(new byte[] {4, 5, 6, 7})));
        HttpURLConnection player = (HttpURLConnection) new URL(stream.url()).openConnection();
        player.setRequestProperty("Range", "bytes=4-7");
        try {
            InputStream input = player.getInputStream();
            while (input.read() != -1) { }
        } finally { player.disconnect(); }
        assertNull(cache.cachedOriginal(track));
    }
}
