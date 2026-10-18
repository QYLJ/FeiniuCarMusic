package com.feiniu.carmusic.ui;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.FnApiClient;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SidebarPlaylistsTest {
    private MockWebServer server;
    private ActivityController<LibraryActivity> controller;
    private LibraryActivity activity;
    private final CountDownLatch listRequested = new CountDownLatch(1), allowList = new CountDownLatch(1);
    private final CountDownLatch firstRequested = new CountDownLatch(1), allowFirst = new CountDownLatch(1);
    private final AtomicInteger playlistReads = new AtomicInteger();
    private boolean delayedList, delayedFirst, empty, failOnce, paged;

    @Before public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ui_preferences", 0).edit().clear().commit();
        Field singleton = FnApiClient.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, null);
        server = new MockWebServer(); server.start();
        FnApiClient.get(context).session().saveLogin(server.url("/").toString().replaceAll("/$", ""), "test", "tester", "local");
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String path = request.getPath();
                if (path.startsWith("/music/api/v1/playlist/list")) {
                    int reads = playlistReads.incrementAndGet();
                    listRequested.countDown();
                    if (delayedList) allowList.await(5, TimeUnit.SECONDS);
                    if (failOnce && reads == 1) return new MockResponse().setResponseCode(500).setBody("failed");
                    return json(empty ? "[]" : paged && path.contains("page=1&")
                            ? "[{\"guid\":\"p1\",\"name\":\"公路\"}]"
                            : paged ? "[{\"guid\":\"p2\",\"name\":\"夜行\"}]"
                            : "[{\"guid\":\"p1\",\"name\":\"公路\"},{\"guid\":\"p2\",\"name\":\"夜行\"}]", empty ? 0 : 2);
                }
                if (path.contains("playlistGUID=p1")) {
                    firstRequested.countDown();
                    if (delayedFirst) allowFirst.await(5, TimeUnit.SECONDS);
                    return json("[{\"guid\":\"s1\",\"title\":\"公路歌曲\"}]", 1);
                }
                if (path.contains("playlistGUID=p2")) return json("[{\"guid\":\"s2\",\"title\":\"夜行歌曲\"}]", 1);
                return json("[{\"guid\":\"home\",\"title\":\"首页歌曲\"}]", 1);
            }
        });
        controller = Robolectric.buildActivity(LibraryActivity.class).create();
        activity = controller.get();
        await(() -> ((ListView) activity.findViewById(R.id.track_list)).getCount() == 1);
    }
    @After public void tearDown() throws Exception {
        allowList.countDown(); allowFirst.countDown();
        controller.destroy(); server.shutdown();
    }
    private MockResponse json(String list, int total) {
        return new MockResponse().setBody("{\"code\":0,\"data\":{\"total\":" + total + ",\"list\":" + list + "}}");
    }
    private void open() { activity.findViewById(R.id.nav_playlists).performClick(); }
    private String title() { return ((TextView) activity.findViewById(R.id.library_title)).getText().toString(); }
    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do { Shadows.shadowOf(Looper.getMainLooper()).idle(); if (condition.getAsBoolean()) return; Thread.sleep(10); }
        while (System.nanoTime() < end);
        fail("Timed out waiting for library state");
    }
    @Test public void selectsPlaylistsInSidebarAndKeepsSongsInExistingPane() throws Exception {
        open();
        ListView list = activity.findViewById(R.id.sidebar_playlist_list);
        await(() -> list.getCount() == 2 && title().equals("公路"));
        assertEquals(View.GONE, activity.findViewById(R.id.collection_grid).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.sidebar_playlists).getVisibility());
        list.performItemClick(null, 1, 1);
        await(() -> title().equals("夜行") && ((ListView) activity.findViewById(R.id.track_list)).getCount() == 1);
        assertTrue(list.getAdapter().getView(1, null, list).isSelected());
        activity.findViewById(R.id.nav_home).performClick();
        open();
        assertEquals("夜行", title());
        assertEquals(1, playlistReads.get());
    }
    @Test public void stalePlaylistResponseCannotReplaceAnotherNavigationPage() throws Exception {
        delayedList = true; open();
        assertTrue(listRequested.await(2, TimeUnit.SECONDS));
        activity.findViewById(R.id.nav_home).performClick();
        allowList.countDown();
        await(() -> ((ListView) activity.findViewById(R.id.sidebar_playlist_list)).getCount() == 2);
        assertEquals("首页", title());
        assertEquals(View.GONE, activity.findViewById(R.id.sidebar_playlists).getVisibility());
    }
    @Test public void staleSongsCannotOverwriteTheNewlySelectedPlaylist() throws Exception {
        delayedFirst = true; open();
        await(() -> ((ListView) activity.findViewById(R.id.sidebar_playlist_list)).getCount() == 2);
        assertTrue(firstRequested.await(2, TimeUnit.SECONDS));
        ((ListView) activity.findViewById(R.id.sidebar_playlist_list)).performItemClick(null, 1, 1);
        await(() -> ((ListView) activity.findViewById(R.id.track_list)).getCount() == 1);
        allowFirst.countDown(); Thread.sleep(100); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("夜行", title());
        assertEquals("s2", ((com.feiniu.carmusic.model.Track) ((ListView) activity.findViewById(R.id.track_list)).getItemAtPosition(0)).guid);
    }
    @Test public void emptyPlaylistsDoNotFallBackToEntireMusicLibrary() throws Exception {
        empty = true; open();
        await(() -> ((TextView) activity.findViewById(R.id.list_status)).getText().toString().equals("暂无歌单"));
        assertEquals(0, ((ListView) activity.findViewById(R.id.track_list)).getCount());
        assertEquals(View.GONE, activity.findViewById(R.id.play_all_button).getVisibility());
    }
    @Test public void failedPlaylistLoadCanRetryInSidebar() throws Exception {
        failOnce = true; open();
        await(() -> ((TextView) activity.findViewById(R.id.sidebar_playlist_status)).getText().toString().contains("重试"));
        activity.findViewById(R.id.sidebar_playlist_status).performClick();
        await(() -> title().equals("公路"));
        assertEquals(2, playlistReads.get());
    }
    @Test public void morePlaylistsUseTheirOwnPagination() throws Exception {
        paged = true; open();
        await(() -> title().equals("公路"));
        activity.findViewById(R.id.sidebar_playlist_status).performClick();
        await(() -> ((ListView) activity.findViewById(R.id.sidebar_playlist_list)).getCount() == 2);
        assertEquals("公路", title());
        assertEquals(2, playlistReads.get());
    }
}
