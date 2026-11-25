package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.model.Track;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueueStoreTest {
    private Context context;
    private QueueStore store;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("playback_queue", 0).edit().clear().commit();
        for (String suffix : new String[] {"", ".bak", ".tmp"}) new File(context.getFilesDir(), "playback-queue.jsonl" + suffix).delete();
        store = new QueueStore(context);
    }
    @After public void tearDown() throws Exception { finish(store); }

    @Test public void largeQueueAndRepeatedProgressSavesRoundTripWithoutGiantPreferenceString() throws Exception {
        List<Track> tracks = new ArrayList<Track>();
        for (int i = 0; i < 12000; i++) {
            Track track = new Track(); track.guid = "song-" + i; track.title = "歌曲 " + i;
            track.artist = "歌手"; track.codec = "flac"; track.bitDepth = 24; track.sampleRate = 96000;
            tracks.add(track);
        }
        store.saveQueue(tracks);
        for (int i = 0; i < 50; i++) store.savePosition(11999, i * 5000);
        finish(store);
        assertFalse(context.getSharedPreferences("playback_queue", 0).contains("tracks"));
        QueueStore restoredStore = new QueueStore(context);
        try {
            QueueStore.RestoredQueue restored = restoredStore.restore();
            assertEquals(12000, restored.tracks.size());
            assertEquals("song-11999", restored.tracks.get(11999).guid);
            assertEquals(24, restored.tracks.get(11999).bitDepth);
            assertEquals(11999, restored.index);
            assertEquals(245000, restored.positionMs);
        } finally { finish(restoredStore); }
    }

    @Test public void legacyQueueMigratesAndCanRecoverInterruptedRenameFromBackup() throws Exception {
        Track track = new Track(); track.guid = "old-song"; track.title = "旧队列"; track.favorite = true;
        context.getSharedPreferences("playback_queue", 0).edit()
                .putString("tracks", new JSONArray().put(track.toJson()).toString()).putInt("position", 1234).commit();
        QueueStore.RestoredQueue legacy = store.restore();
        assertEquals("old-song", legacy.tracks.get(0).guid);
        assertTrue(legacy.tracks.get(0).favorite);
        finish(store);
        File file = new File(context.getFilesDir(), "playback-queue.jsonl");
        assertTrue(file.renameTo(new File(file.getPath() + ".bak")));
        QueueStore restoredStore = new QueueStore(context);
        try {
            QueueStore.RestoredQueue restored = restoredStore.restore();
            assertEquals("旧队列", restored.tracks.get(0).title);
            assertEquals(1234, restored.positionMs);
        } finally { finish(restoredStore); }
    }

    @Test public void clearWinsOverPendingPageAppends() throws Exception {
        Track track = new Track(); track.guid = "discarded";
        for (int i = 0; i < 30; i++) store.saveQueue(Collections.singletonList(track));
        store.clear();
        finish(store);
        assertTrue(store.restore().tracks.isEmpty());
        assertEquals(-1, store.restore().index);
    }

    @Test public void switchingAccountWithoutRunningServiceClearsStoredQueueAndHistory() throws Exception {
        Track track = new Track(); track.guid = "previous-account-song";
        store.saveQueue(Collections.singletonList(track));
        store.savePosition(0, 3500);
        com.feiniu.carmusic.data.HistoryStore history = new com.feiniu.carmusic.data.HistoryStore(context);
        history.add(track);
        finish(store);

        PlaybackService.resetForAccountSwitch(context);
        assertTrue(new QueueStore(context).restore().tracks.isEmpty());
        assertEquals(-1, context.getSharedPreferences("playback_queue", 0).getInt("index", 0));
        assertTrue(history.list().isEmpty());
    }

    private static void finish(QueueStore target) throws Exception {
        target.close();
        Field writer = QueueStore.class.getDeclaredField("writer"); writer.setAccessible(true);
        ((ExecutorService) writer.get(target)).submit(new Runnable() {
            @Override public void run() { }
        }).get(15, TimeUnit.SECONDS);
    }
}
