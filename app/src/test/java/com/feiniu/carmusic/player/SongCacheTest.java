package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SongCacheTest {
    private Context context;
    private SongCache cache;
    private File directory;
    private static final long MB = 1024L * 1024L;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("song_cache", 0).edit().clear().commit();
        context.getSharedPreferences("fn_session", 0).edit().clear().commit();
        cache = new SongCache(context);
        directory = new File(context.getCacheDir(), "tracks");
    }

    @Test public void limitPersistsAndSupportsLargeStorageWithoutIntegerOverflow() {
        assertEquals(512, cache.limitMb());
        assertTrue(cache.setLimitMb(8192));
        assertEquals(8192, new SongCache(context).limitMb());
        assertFalse(cache.setLimitMb(0));
        assertFalse(cache.setLimitMb(65537));
        assertEquals(8192, cache.limitMb());
        assertEquals("8.0 GB", SongCache.formatBytes(cache.limitMb() * MB));
    }

    @Test public void reducingLimitEvictsLeastRecentlyUsedSongsWithTheirDescriptors() throws Exception {
        File oldest = song("oldest", 25 * MB, 1000);
        File oftenPlayed = song("frequent", 25 * MB, 3000);
        File newer = song("newer", 25 * MB, 2000);
        assertTrue(cache.setLimitMb(64));
        SongCache.Usage usage = cache.trimToLimit();
        assertFalse(oldest.exists());
        assertFalse(new File(oldest.getPath() + ".audio").exists());
        assertFalse(new File(oldest.getPath() + ".complete").exists());
        assertTrue(oftenPlayed.exists());
        assertTrue(newer.exists());
        assertEquals(2, usage.songs);
        assertTrue(usage.bytes <= 64 * MB);
    }

    @Test public void oversizeActiveSongSurvivesShrinkAndIsReclaimedAfterRelease() throws Exception {
        File playing = song("playing", 70 * MB, 1000);
        SongCache.Lease lease = cache.protect(playing);
        try {
            File other = song("other", 10 * MB, 2000);
            cache.setLimitMb(64);
            assertTrue(cache.trimToLimit().bytes > 64 * MB);
            assertTrue(playing.exists());
            assertFalse(other.exists());
        } finally { lease.close(); }
        cache.trimToLimit();
        assertFalse(playing.exists());
        assertEquals(0L, cache.usage().bytes);
    }

    @Test public void clearKeepsPlaybackDownloadAndStreamingTemporaryFilesButCleansUnusedSongs() throws Exception {
        File playing = song("playing", 10, 1000);
        File download = new File(directory, "downloading.track");
        File temporary = cache.createTemporary();
        SongCache.Lease playingLease = cache.protect(playing);
        SongCache.Lease downloadLease = cache.protect(download);
        SongCache.Lease temporaryLease = cache.temporaryLease(temporary);
        try {
            try (FileOutputStream output = new FileOutputStream(download)) { output.write(new byte[] {1, 2}); }
            File other = song("unused", 10, 2000);
            File unrelated = new File(directory, "keep.txt");
            assertTrue(unrelated.createNewFile());
            cache.clearUnused();
            assertTrue(playing.exists());
            assertTrue(download.exists());
            assertTrue(temporary.exists());
            assertTrue(unrelated.exists());
            assertFalse(other.exists());
        } finally { playingLease.close(); downloadLease.close(); temporaryLease.close(); }
        assertEquals(0L, cache.clearUnused().bytes);
        assertFalse(temporary.exists());
    }

    @Test public void fnIdRouteChangeReusesCompleteSongWithoutAnotherNasRequestAndIsolatesAccounts() throws Exception {
        MockWebServer nas = new MockWebServer(); nas.start();
        try {
            FnApiClient api = FnApiClient.get(context);
            api.session().saveLogin(nas.url("/").toString().replaceAll("/$", ""), "test-token", "tester", "testnas");
            Track track = new Track(); track.guid = "frequent-song"; track.codec = "mp3";
            byte[] bytes = new byte[] {1, 3, 5, 7};
            nas.enqueue(new MockResponse().setBody(new Buffer().write(bytes)));
            TrackFileCache files = new TrackFileCache(context);
            AudioFile first = files.getOrDownload(track, "");
            assertTrue(first.file.setLastModified(1000L));
            api.session().saveLogin("http://127.0.0.1:1", "test-token", "tester", "testnas");
            AudioFile cached = files.getOrDownload(track, "");
            assertEquals(first.file, cached.file);
            assertTrue(cached.file.lastModified() > 1000L);
            assertEquals(4, cached.file.length());
            assertEquals(1, nas.getRequestCount());
            api.session().saveLogin("http://127.0.0.1:1", "other-token", "other-user", "testnas");
            assertNull(files.cachedOriginal(track));
        } finally { nas.shutdown(); }
    }

    @Test public void existingVersionCacheMigratesToStableFnIdKeyWithoutRedownload() throws Exception {
        String base = "http://127.0.0.1:1";
        FnApiClient.get(context).session().saveLogin(base, "test-token", "tester", "testnas");
        Track track = new Track(); track.guid = "old-cached-song"; track.codec = "mp3";
        String oldKey = base + "|tester|" + track.guid + "||stream-audio-v3";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(oldKey.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte value : digest) name.append(String.format("%02x", value & 255));
        File old = song(name.toString(), 10, System.currentTimeMillis());
        AudioFile restored = new TrackFileCache(context).getOrDownload(track, "");
        assertNotEquals(old, restored.file);
        assertEquals(10, restored.file.length());
        assertFalse(old.exists());
        assertEquals(1, cache.usage().songs);
    }

    private File song(String name, long bytes, long accessedAt) throws Exception {
        File target = new File(directory, name + ".track");
        try (RandomAccessFile output = new RandomAccessFile(target, "rw")) { output.setLength(bytes); }
        JSONObject descriptor = new JSONObject().put("codec", "mp3").put("sampleRate", 44100)
                .put("bitDepth", 0).put("channels", 2).put("transcoded", false);
        try (FileOutputStream output = new FileOutputStream(target.getPath() + ".audio")) {
            output.write(descriptor.toString().getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(new File(target.getPath() + ".complete").createNewFile());
        assertTrue(target.setLastModified(accessedAt));
        return target;
    }
}
