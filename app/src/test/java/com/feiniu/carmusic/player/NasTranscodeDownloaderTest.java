package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.json.JSONObject;
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
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class NasTranscodeDownloaderTest {
    private MockWebServer server;
    private FnApiClient api;
    private Context context;
    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Field singleton = FnApiClient.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, null);
        server = new MockWebServer(); server.start();
        api = FnApiClient.get(context);
        api.session().saveLogin(server.url("/").toString().replaceAll("/$", ""), "test-token", "tester", "local");
    }
    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void authenticatedMp3AndFlacSessionsProduceCompleteFilesAndRelease() throws Exception {
        for (String codec : new String[] {"mp3", "flac"}) {
            enqueueSession(codec);
            File target = new File(context.getCacheDir(), "nas-" + codec + ".track");
            AudioFile audio = new NasTranscodeDownloader(api).download("song", codec, target);
            assertTrue(target.length() > 1000);
            assertEquals(codec, audio.codec); assertEquals(44100, audio.sampleRate); assertEquals(2, audio.channels);
            RecordedRequest start = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/music/api/v1/track/transcode", start.getPath());
            assertEquals("music-token=test-token", start.getHeader("Cookie"));
            JSONObject body = new JSONObject(start.getBody().readUtf8());
            assertEquals(codec, body.getJSONObject("output").getString("codec"));
            assertEquals(2, body.getJSONObject("output").getInt("channel"));
            assertEquals("mp3".equals(codec) ? 192000 : 320, body.getJSONObject("output").getInt("bitrate"));
            for (int i = 0; i < 7; i++) assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
            assertEquals("/music/api/v1/track/transcode/quit", server.takeRequest(1, TimeUnit.SECONDS).getPath());
        }
    }

    @Test public void earlyAudioIsReadableBeforeRemainingSegmentsAndCacheMarkerWaitsUntilComplete() throws Exception {
        enqueueSession("mp3", true);
        Track track = new Track(); track.guid = "early-stream";
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        final ProgressiveAudioFile[] stream = {null};
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<AudioFile> future = executor.submit(() -> new TrackFileCache(context)
                    .getOrDownload(track, "mp3", 320, audio -> { stream[0] = audio; ready.countDown(); }));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            assertFalse(stream[0].isComplete());
            assertFalse(new File(stream[0].audio.file.getPath() + ".complete").exists());
            assertEquals(-1, stream[0].size());
            assertTrue(stream[0].readAt(0, new byte[4], 0, 4) > 0);
            AudioFile complete = future.get(8, TimeUnit.SECONDS);
            assertTrue(stream[0].isComplete());
            assertEquals(complete.file.length(), stream[0].size());
            assertEquals(192, complete.bitrateKbps); // The synthetic server really encodes 192.
            assertTrue(new File(complete.file.getPath() + ".complete").exists());
            assertEquals(320000, new JSONObject(server.takeRequest().getBody().readUtf8())
                    .getJSONObject("output").getInt("bitrate"));
        } finally {
            if (stream[0] != null) stream[0].close();
            executor.shutdownNow();
        }
    }

    @Test public void cacheSeparatesSourceAndTranscodeProfilesAndNeverPublishesPartialFile() throws Exception {
        Track track = new Track(); track.guid = "cache-song"; track.codec = "mp3";
        TrackFileCache cache = new TrackFileCache(context);
        byte[] original = resource("packed.mp3");
        server.enqueue(new MockResponse().setBody(new Buffer().write(original)));
        AudioFile raw = cache.getOrDownload(track, "");
        assertEquals(original.length, raw.file.length());
        assertTrue(new File(raw.file.getPath() + ".complete").isFile());
        assertEquals(raw.file, cache.getOrDownload(track, "").file);
        assertEquals(1, server.getRequestCount());
        server.takeRequest();
        enqueueSession("mp3");
        AudioFile transcode = cache.getOrDownload(track, "mp3");
        assertNotEquals(raw.file, transcode.file);
        assertTrue(transcode.transcoded); assertFalse(raw.transcoded);
        assertEquals(transcode.file, cache.getOrDownload(track, "mp3").file);
        assertEquals(10, server.getRequestCount());
    }

    @Test public void differentRequestedBitratesNeverReuseAnotherEncodingProfile() throws Exception {
        Track track = new Track(); track.guid = "bitrate-profiles";
        TrackFileCache cache = new TrackFileCache(context);
        enqueueSession("mp3");
        AudioFile first = cache.getOrDownload(track, "mp3", 128, null);
        enqueueSession("mp3");
        AudioFile second = cache.getOrDownload(track, "mp3", 320, null);
        assertNotEquals(first.file, second.file);
        assertEquals(first.file, cache.getOrDownload(track, "mp3", 128, null).file);
        assertEquals(second.file, cache.getOrDownload(track, "mp3", 320, null).file);
        assertEquals(18, server.getRequestCount());
        assertEquals(192, first.bitrateKbps);
        assertEquals(192, second.bitrateKbps); // Display the actual encoding, not just the requested value.
    }

    @Test public void unsupportedServerResponseIsReleasedAndNotCached() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"status\":\"failed\",\"errmsg\":\"unsupported codec\"}}"));
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"status\":\"success\"}}"));
        Track track = new Track(); track.guid = "unsupported-song";
        try { new TrackFileCache(context).getOrDownload(track, "mp3"); fail("Failed transcode cannot be played"); }
        catch (Exception error) { assertTrue(error.getMessage().contains("unsupported codec")); }
        assertEquals("/music/api/v1/track/transcode", server.takeRequest().getPath());
        assertEquals("/music/api/v1/track/transcode/quit", server.takeRequest().getPath());
    }

    @Test public void resourceCannotExportCookieToOtherOriginOrFollowRedirect() throws Exception {
        MockWebServer other = new MockWebServer(); other.start();
        try {
            try { api.readMusicResource(other.url("/private").toString(), 1000); fail("Cross-origin rejected"); }
            catch (Exception expected) { assertTrue(expected.getMessage().contains("其他服务器")); }
            assertEquals(0, other.getRequestCount());
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", other.url("/private").toString()));
            try { api.readMusicResource(server.url("/redirect").toString(), 1000); fail("Redirect rejected"); }
            catch (Exception expected) { assertTrue(expected.getMessage().contains("302")); }
            assertEquals(0, other.getRequestCount());
        } finally { other.shutdown(); }
    }

    @Test public void playlistResolvesMapAndRelativeSegmentsAndRejectsUnsafeLayouts() throws Exception {
        NasTranscodeDownloader.Playlist playlist = NasTranscodeDownloader.parsePlaylist("http://nas/music/preset.m3u8",
                "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2.5,\n0.m4s\n#EXT-X-ENDLIST");
        assertEquals("http://nas/music/init.mp4", playlist.segments.get(0).mapUrl);
        assertEquals(7, playlist.segments.get(0).sequence);
        assertEquals(2.5, playlist.segments.get(0).duration, 0.0);
        assertTrue(playlist.complete);
        try { NasTranscodeDownloader.parsePlaylist("http://nas/a", "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\""); fail(); }
        catch (Exception expected) { assertTrue(expected.getMessage().contains("加密")); }
    }

    @Test public void heartbeatUsesPlaybackSecondsAndNotWallClock() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{}}"));
        api.transcodeHeartbeat("song", 1.234);
        JSONObject body = new JSONObject(server.takeRequest().getBody().readUtf8());
        assertEquals(1.234, body.getDouble("timestamp"), 0.0);
    }

    private void enqueueSession(String codec) throws Exception {
        enqueueSession(codec, false);
    }
    private void enqueueSession(String codec, boolean slow) throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"status\":\"ready\"}}"));
        StringBuilder playlist = new StringBuilder("#EXTM3U\n#EXT-X-MAP:URI=\"")
                .append(codec).append("-init.mp4\"\n");
        for (int i = 0; i < 5; i++) playlist.append("#EXTINF:0.25,\n").append(codec).append('-').append(i).append(".m4s\n");
        playlist.append("#EXT-X-ENDLIST\n");
        server.enqueue(new MockResponse().setBody(playlist.toString()));
        server.enqueue(new MockResponse().setBody(new Buffer().write(resource(codec + "-init.mp4"))));
        for (int i = 0; i < 5; i++) {
            MockResponse response = new MockResponse().setBody(new Buffer().write(resource(codec + "-" + i + ".m4s")));
            if (slow && i == 1) response.setBodyDelay(1, TimeUnit.SECONDS);
            server.enqueue(response);
        }
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"status\":\"success\"}}"));
    }
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
