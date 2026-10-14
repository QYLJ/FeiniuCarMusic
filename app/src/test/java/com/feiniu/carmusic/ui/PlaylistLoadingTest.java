package com.feiniu.carmusic.ui;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;
import android.widget.GridView;
import android.widget.ListView;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import com.feiniu.carmusic.player.PlaybackService;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaylistLoadingTest {
    private MockWebServer server;
    private ActivityController<LibraryActivity> controller;
    private LibraryActivity activity;
    private final AtomicInteger serverPageSize = new AtomicInteger(500);
    private final AtomicInteger playlistRequests = new AtomicInteger();
    private final CountDownLatch releaseLaterPages = new CountDownLatch(1);
    private final AtomicInteger reportedTotal = new AtomicInteger(600);
    private volatile boolean repeatFirstPage;
    private volatile String playlistQuery;

    @Before public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("fn_session", 0).edit().clear().commit();
        context.getSharedPreferences("playback_queue", 0).edit().clear().commit();
        Field singleton = FnApiClient.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        singleton.set(null, null);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    String path = request.getRequestUrl().encodedPath();
                    JSONObject data = new JSONObject();
                    if (path.endsWith("/playlist/list")) {
                        data.put("total", 1).put("list", new JSONArray().put(new JSONObject()
                                .put("guid", "playlist-1").put("name", "600 首测试歌单").put("trackCount", 600)));
                    } else if (path.endsWith("/playlist-detail/list")) {
                        playlistQuery = request.getRequestUrl().encodedQuery();
                        playlistRequests.incrementAndGet();
                        int page = Integer.parseInt(request.getRequestUrl().queryParameter("page"));
                        int size = serverPageSize.get();
                        if (page > 1) releaseLaterPages.await(10, TimeUnit.SECONDS);
                        int start = repeatFirstPage ? 0 : (page - 1) * size;
                        JSONArray tracks = new JSONArray();
                        for (int i = start; i < Math.min(600, start + size); i++) {
                            tracks.put(new JSONObject().put("guid", "track-" + i).put("title", "歌曲 " + i));
                        }
                        data.put("total", reportedTotal.get()).put("list", tracks);
                    } else {
                        data.put("total", 0).put("list", new JSONArray());
                    }
                    return new MockResponse().setBody(new JSONObject().put("code", 0).put("data", data).toString());
                } catch (Exception error) { throw new AssertionError(error); }
            }
        });
        server.start();
        FnApiClient.get(context).session().saveLogin(server.url("/").toString().replaceAll("/$", ""),
                "test-token", "test-user", "localhost");
        // Create without resuming: exercise list UI/networking without a public update check.
        controller = Robolectric.buildActivity(LibraryActivity.class).create();
        activity = controller.get();
        awaitLoad();
        activity.findViewById(R.id.nav_playlists).performClick();
        GridView grid = (GridView) activity.findViewById(R.id.collection_grid);
        awaitCount(grid, 1);
        grid.performItemClick(null, 0, 0);
        awaitTracks(500);
    }

    @After public void tearDown() throws Exception {
        if (controller != null) controller.destroy();
        releaseLaterPages.countDown();
        server.shutdown();
    }

    @Test public void playlistAutomaticallyLoadsRemainingSongsAndAppendsWithoutRestartingQueue() throws Exception {
        ListView list = (ListView) activity.findViewById(R.id.track_list);
        assertEquals(500, list.getCount());
        assertTrue(playlistQuery.contains("size=500"));
        assertTrue(playlistQuery.contains("playlistGUID=playlist-1"));
        assertFalse(field("allLoaded").getBoolean(activity));

        ServiceController<PlaybackService> serviceController = Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = serviceController.get();
            field("playback").set(activity, service);
            // Queue creation is independent of audio decoding and downloading.
            List<Track> loaded = (List<Track>) field("tracks").get(activity);
            loaded.get(0).accessStatus = 3;
            loaded.get(234).accessStatus = 3;
            activity.findViewById(R.id.play_all_button).performClick();
            JSONArray saved = new JSONArray(service.getSharedPreferences("playback_queue", 0).getString("tracks", "[]"));
            assertEquals(500, saved.length());
            assertEquals("track-499", saved.getJSONObject(499).getString("guid"));
            service.playAt(234);
            service.seekTo(42000);
            Field pendingSeek = PlaybackService.class.getDeclaredField("pendingSeekMs");
            pendingSeek.setAccessible(true);
            // While the library is stopped behind the player, later pages still fill this queue.
            shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                    new ComponentName(activity, PlaybackService.class), service.onBind(new Intent()));
            controller.start();
            shadowOf(Looper.getMainLooper()).idle();
            controller.stop();
            assertSame(service, field("playback").get(activity));
            releaseLaterPages.countDown();
            awaitComplete();
            assertEquals(600, list.getCount());
            assertEquals(600, service.getQueueSnapshot().size());
            assertEquals(234, service.snapshot().queueIndex);
            assertEquals(42000, pendingSeek.getInt(service));
            JSONArray complete = new JSONArray(service.getSharedPreferences("playback_queue", 0).getString("tracks", "[]"));
            assertEquals(600, complete.length());
            assertEquals("track-599", complete.getJSONObject(599).getString("guid"));
        } finally {
            field("playback").set(activity, null);
            serviceController.destroy();
        }

        assertEquals(600, list.getCount());
        assertTrue(field("allLoaded").getBoolean(activity));
        assertEquals(2, playlistRequests.get());
        loadNext();
        assertEquals(2, playlistRequests.get());
    }

    @Test public void serverReturningShortPagesDoesNotStopBeforeReportedTotal() throws Exception {
        // Reopen with a server that caps each response to 100 despite size=500.
        serverPageSize.set(100);
        activity.findViewById(R.id.nav_playlists).performClick();
        GridView grid = (GridView) activity.findViewById(R.id.collection_grid);
        awaitCount(grid, 1);
        grid.performItemClick(null, 0, 0);
        awaitTracks(100);
        ListView list = (ListView) activity.findViewById(R.id.track_list);
        assertEquals(100, list.getCount());
        assertFalse(field("allLoaded").getBoolean(activity));
        releaseLaterPages.countDown();
        awaitComplete();
        assertEquals(600, list.getCount());
        assertTrue(field("allLoaded").getBoolean(activity));
        assertEquals("track-599", ((Track) list.getItemAtPosition(599)).guid);
    }

    @Test public void unknownTotalContinuesAfterShortPageUntilEmptyPage() throws Exception {
        reportedTotal.set(0);
        releaseLaterPages.countDown();
        awaitComplete();
        assertEquals(600, ((ListView) activity.findViewById(R.id.track_list)).getCount());
        assertEquals(3, playlistRequests.get());
    }

    @Test public void repeatedServerPagesDoNotLoadForever() throws Exception {
        repeatFirstPage = true;
        releaseLaterPages.countDown();
        awaitComplete();
        assertEquals(500, ((ListView) activity.findViewById(R.id.track_list)).getCount());
        assertEquals(2, playlistRequests.get());
    }

    @Test public void laterPagesCannotAppendToAReplacedQueue() throws Exception {
        ServiceController<PlaybackService> serviceController = Robolectric.buildService(PlaybackService.class).create();
        try {
            PlaybackService service = serviceController.get();
            field("playback").set(activity, service);
            List<Track> loaded = (List<Track>) field("tracks").get(activity);
            loaded.get(0).accessStatus = 3;
            activity.findViewById(R.id.play_all_button).performClick();
            Track replacement = new Track();
            replacement.guid = "other-playlist-track";
            replacement.accessStatus = 3;
            service.setQueueAndPlay(java.util.Collections.singletonList(replacement), 0);
            releaseLaterPages.countDown();
            awaitComplete();
            assertEquals(1, service.getQueueSnapshot().size());
            assertEquals("other-playlist-track", service.getQueueSnapshot().get(0).guid);
        } finally {
            field("playback").set(activity, null);
            serviceController.destroy();
        }
    }

    private void awaitTracks(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (((ListView) activity.findViewById(R.id.track_list)).getCount() == count) return;
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("First batch did not load");
    }

    private void awaitComplete() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (field("allLoaded").getBoolean(activity) && !field("loading").getBoolean(activity)) return;
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("Automatic pagination did not complete");
    }

    private void loadNext() throws Exception {
        Method method = LibraryActivity.class.getDeclaredMethod("loadNextPage");
        method.setAccessible(true);
        method.invoke(activity);
    }

    private Field field(String name) throws Exception {
        Field field = LibraryActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void awaitLoad() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (!field("loading").getBoolean(activity)) return;
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("Track loading did not complete");
    }

    private void awaitCount(GridView grid, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (grid.getCount() == count) return;
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("Collection loading did not complete");
    }
}
