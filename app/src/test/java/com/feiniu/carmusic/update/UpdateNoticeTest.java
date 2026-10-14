package com.feiniu.carmusic.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.TextView;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class UpdateNoticeTest {
    private MockWebServer server;
    private ActivityController<Activity> controller;
    private Activity activity;
    private String manifestUrl;
    private int currentCode;

    @Before public void setUp() throws Exception {
        resetUpdater();
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        prefs().edit().clear().commit();
        currentCode = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
        server = new MockWebServer();
        server.start();
        manifestUrl = server.url("/updates/update.json").toString();
    }

    @After public void tearDown() throws Exception {
        AppUpdater.onPause(activity);
        controller.pause().stop().destroy();
        server.shutdown();
        resetUpdater();
    }

    @Test public void cachedNoticeShowsNotesAndLaterDoesNotRepeatUntilRestart() throws Exception {
        cache(manifest(currentCode + 1, 14));
        AppUpdater.onResume(activity, manifestUrl);
        AlertDialog dialog = dialog();
        String message = ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
        assertTrue(message.contains("当前版本："));
        assertTrue(message.contains("新版本：0.3.99"));
        assertTrue(message.contains("更新内容：\n1. 歌单每批加载 500 首\n2. 新版提示"));
        assertEquals("下载并安装", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AppUpdater.onPause(activity);
        AppUpdater.onResume(activity, manifestUrl);
        assertFalse(dialog.isShowing());
        assertNull(field("availableDialog").get(null));
        assertEquals(0, server.getRequestCount());

        // Process restart: the successful check remains cached, but a new session reminds again.
        resetUpdater();
        AppUpdater.onResume(activity, manifestUrl);
        assertTrue(dialog().isShowing());
        assertEquals(0, server.getRequestCount());
    }

    @Test public void resultCompletedInBackgroundIsShownOnNextScreenWithoutAnotherRequest() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                responseReady.await(5, TimeUnit.SECONDS);
                try { return new MockResponse().setBody(manifest(currentCode + 1, 14).toString()); }
                catch (Exception error) { throw new AssertionError(error); }
            }
        });
        AppUpdater.onResume(activity, manifestUrl);
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
        AppUpdater.onPause(activity);
        responseReady.countDown();
        awaitCheck();
        assertNull(field("availableDialog").get(null));
        assertTrue(prefs().getLong("last_check", 0L) > 0L);

        ActivityController<Activity> next = Robolectric.buildActivity(Activity.class).setup();
        try {
            AppUpdater.onResume(next.get(), manifestUrl);
            assertTrue(dialog().isShowing());
            assertEquals(next.get(), dialog().getOwnerActivity());
            assertEquals(1, server.getRequestCount());
        } finally {
            AppUpdater.onPause(next.get());
            next.pause().stop().destroy();
        }
    }

    @Test public void navigationDoesNotTreatHiddenNoticeAsChoosingLater() throws Exception {
        cache(manifest(currentCode + 1, 14));
        AppUpdater.onResume(activity, manifestUrl);
        AlertDialog first = dialog();
        AppUpdater.onPause(activity);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(first.isShowing());
        AppUpdater.onResume(activity, manifestUrl);
        assertTrue(dialog().isShowing());
        assertNotSame(first, dialog());
    }

    @Test public void manualCheckCanRemindAgainAndAlwaysFetchesLatestManifest() throws Exception {
        cache(manifest(currentCode + 1, 14));
        AppUpdater.onResume(activity, manifestUrl);
        dialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        server.enqueue(new MockResponse().setBody(manifest(currentCode + 1, 14).toString()));
        AppUpdater.check(activity, true, manifestUrl);
        awaitCheck();
        assertEquals(1, server.getRequestCount());
        assertTrue(dialog().isShowing());
    }

    @Test public void installedAndIncompatibleVersionsDoNotPromptAutomatically() throws Exception {
        cache(manifest(currentCode, 14));
        AppUpdater.onResume(activity, manifestUrl);
        assertNull(field("availableDialog").get(null));
        cache(manifest(currentCode + 1, 99));
        AppUpdater.onResume(activity, manifestUrl);
        assertNull(field("availableDialog").get(null));
        assertEquals(0, server.getRequestCount());
    }

    @Test public void corruptCacheCannotSuppressFreshCheckAndFailureDoesNotThrottleRetry() throws Exception {
        cache(manifest(currentCode + 1, 14));
        prefs().edit().putString("manifest", "broken").commit();
        server.enqueue(new MockResponse().setResponseCode(503));
        AppUpdater.onResume(activity, manifestUrl);
        awaitCheck();
        assertEquals(0L, prefs().getLong("last_check", 0L));
        assertNull(field("availableDialog").get(null));
        server.enqueue(new MockResponse().setBody(manifest(currentCode + 1, 14).toString()));
        AppUpdater.onResume(activity, manifestUrl);
        awaitCheck();
        assertEquals(2, server.getRequestCount());
        assertTrue(dialog().isShowing());
    }

    private JSONObject manifest(int code, int minSdk) throws Exception {
        return new JSONObject().put("versionCode", code).put("versionName", "0.3.99")
                .put("apkUrl", "app.apk").put("sha256", new String(new char[64]).replace('\0', 'A'))
                .put("minSdk", minSdk).put("changelog", "1. 歌单每批加载 500 首\n2. 新版提示");
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences("app_updater", Context.MODE_PRIVATE);
    }

    private void cache(JSONObject json) {
        prefs().edit().putString("manifest_source", manifestUrl).putString("manifest", json.toString())
                .putLong("last_check", System.currentTimeMillis()).commit();
    }

    private AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private void awaitCheck() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (!field("checking").getBoolean(null)) return;
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        fail("Update check did not complete");
    }

    private static Field field(String name) throws Exception {
        Field field = AppUpdater.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void resetUpdater() throws Exception {
        field("foregroundActivity").set(null, new WeakReference<Activity>(null));
        field("availableDialog").set(null, null);
        field("promptedVersionCode").setInt(null, 0);
        field("checking").setBoolean(null, false);
        field("downloading").setBoolean(null, false);
    }
}
