package com.feiniu.carmusic.player;

import android.content.Context;
import android.os.Looper;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowMediaPlayer;
import java.lang.reflect.Field;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 22})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LegacyOriginalPlaybackTest {
    @Test public void legacyOriginalStartsThroughLoopbackWithoutWaitingForDownloadAndClosesOnStop() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("playback_queue", 0).edit().clear().commit();
        context.getSharedPreferences("playback_settings", 0).edit().clear().commit();
        FnApiClient.get(context).session().saveLogin("http://127.0.0.1:1", "test-token", "tester", "127.0.0.1");
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(60000, 0));
        ServiceController<PlaybackService> controller = Robolectric.buildService(PlaybackService.class).create();
        PlaybackService service = controller.get();
        OriginalAudioStream original = null;
        try {
            Track track = new Track(); track.guid = "legacy-source"; track.codec = "mp3"; track.durationMs = 60000;
            service.setQueueAndPlay(Collections.singletonList(track), 0);
            for (int i = 0; i < 200 && original == null; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                original = (OriginalAudioStream) field("originalStream").get(service);
                Thread.sleep(10);
            }
            assertNotNull("Old systems should prepare the source before downloading a complete file", original);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(service.snapshot().playing);
            assertTrue(service.snapshot().audioDescription.startsWith("原始"));
            assertNull(field("progressiveAudio").get(service));
            assertFalse(field("usingTranscode").getBoolean(service));
        } finally { controller.destroy(); }
        assertNull(field("originalStream").get(service));
    }

    @Test public void clearingCacheKeepsTheServicesCurrentSongUntilPlaybackReleasesIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("playback_queue", 0).edit().clear().commit();
        context.getSharedPreferences("playback_settings", 0).edit().clear().commit();
        context.getSharedPreferences("song_cache", 0).edit().clear().commit();
        FnApiClient.get(context).session().saveLogin("http://127.0.0.1:1", "test-token", "tester", "127.0.0.1");
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(60000, 0));
        Track track = new Track(); track.guid = "protected-song"; track.codec = "mp3"; track.durationMs = 60000;
        TrackFileCache files = new TrackFileCache(context);
        TrackFileCache.StreamCache output = files.cacheOriginalStream(track);
        try { output.write(new byte[] {1, 2, 3, 4}, 4); output.finish(4); }
        finally { output.close(); }
        AudioFile audio = files.cachedOriginal(track);
        assertNotNull(audio);
        ServiceController<PlaybackService> controller = Robolectric.buildService(PlaybackService.class).create();
        try {
            controller.get().setQueueAndPlay(Collections.singletonList(track), 0);
            new SongCache(context).clearUnused();
            assertTrue("Clearing unused songs must not delete the pending or playing source", audio.file.isFile());
            for (int i = 0; i < 200 && !controller.get().snapshot().playing; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertTrue(controller.get().snapshot().playing);
            assertTrue(audio.file.isFile());
        } finally { controller.destroy(); }
        new SongCache(context).clearUnused();
        assertFalse(audio.file.exists());
    }

    @Test public void rejectedFileDescriptorRetriesTheCachedSongOverLoopback() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        FnApiClient.get(context).session().saveLogin("http://127.0.0.1:1", "test-token", "tester", "127.0.0.1");
        Track song = new Track(); song.guid = "fd-retry"; song.codec = "mp3"; song.durationMs = 60000;
        TrackFileCache cache = new TrackFileCache(context);
        try (TrackFileCache.StreamCache out = cache.cacheOriginalStream(song)) {
            out.write(new byte[] {1, 2, 3, 4}, 4);
            out.finish(4);
        }
        java.util.concurrent.atomic.AtomicBoolean firstSource = new java.util.concurrent.atomic.AtomicBoolean(true);
        ShadowMediaPlayer.setMediaInfoProvider(source -> {
            if (firstSource.getAndSet(false)) throw new IllegalArgumentException("simulated setDataSourceFD failed");
            return new ShadowMediaPlayer.MediaInfo(60000, 0);
        });
        ServiceController<PlaybackService> controller = Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = controller.get();
            service.setQueueAndPlay(Collections.singletonList(song), 0);
            for (int i = 0; i < 200 && !service.snapshot().playing; i++) {
                shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(10);
            }
            assertTrue(service.snapshot().playing);
            assertNotNull(field("localStream").get(service));
            assertNull(field("originalStream").get(service));
            assertTrue(service.getAudioDiagnostics().contains("本机 HTTP"));
        } finally { controller.destroy(); }
    }

    private static Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
