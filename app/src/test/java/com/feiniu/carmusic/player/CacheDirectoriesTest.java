package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 28, 35}, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CacheDirectoriesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("song_cache", 0).edit().clear().commit();
    }
    @Test public void selectionSurvivesRecreationAndUnplugFallsBackWithoutRecreatingMount() throws Exception {
        File volume = temporary.newFolder("usb");
        File selected = CacheDirectories.select(context, volume);
        assertEquals(new File(volume, "FeiniuCarMusic-cache").getCanonicalFile(), selected);
        assertEquals(selected, CacheDirectories.resolve(context));
        File renamed = new File(temporary.getRoot(), "unmounted");
        assertTrue(volume.renameTo(renamed));
        assertEquals(CacheDirectories.internal(context), CacheDirectories.resolve(context));
        assertFalse(volume.exists());
        assertEquals(selected.getAbsolutePath(), CacheDirectories.selected(context));
        assertTrue(renamed.renameTo(volume));
        assertEquals(selected, CacheDirectories.resolve(context));
    }
    @Test public void invalidSelectionKeepsPreviousSetting() throws Exception {
        File original = CacheDirectories.select(context, temporary.newFolder("tf"));
        File notDirectory = temporary.newFile("read-only-volume");
        try { CacheDirectories.select(context, notDirectory); fail(); } catch (IOException expected) { }
        assertEquals(original, CacheDirectories.resolve(context));
    }
    @Test public void existingCacheOwnerUsesNewSelectionForSubsequentSongsAndKeepsActiveOldFile() throws Exception {
        TrackFileCache cache = new TrackFileCache(context);
        Track oldTrack = track("internal"), externalTrack = track("external");
        TrackFileCache.StreamCache oldDownload = cache.cacheOriginalStream(oldTrack);
        File external = CacheDirectories.select(context, temporary.newFolder("tf"));
        try {
            oldDownload.write(new byte[] {1,2,3}, 3); oldDownload.finish(3);
        } finally { oldDownload.close(); }
        TrackFileCache.StreamCache newDownload = cache.cacheOriginalStream(externalTrack);
        try { newDownload.write(new byte[] {4,5,6}, 3); newDownload.finish(3); }
        finally { newDownload.close(); }
        AudioFile audio = cache.cachedOriginal(externalTrack);
        assertNotNull(audio); assertEquals(external, audio.file.getParentFile());
        assertNull(cache.cachedOriginal(oldTrack));
        CacheDirectories.select(context, null);
        assertNotNull(cache.cachedOriginal(oldTrack));
        assertTrue(audio.file.exists());
    }
    private Track track(String id) { Track track = new Track(); track.guid = id; track.codec = "mp3"; return track; }
}
