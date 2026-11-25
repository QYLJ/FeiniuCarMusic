package com.feiniu.carmusic.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.EditText;
import android.view.View;
import android.widget.TextView;
import com.feiniu.carmusic.player.SongCache;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 28})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CacheSettingsTest {
    private Context context;
    private ActivityController<SettingsActivity> controller;
    private SettingsActivity activity;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("song_cache", 0).edit().clear().commit();
        controller = Robolectric.buildActivity(SettingsActivity.class).create();
        activity = controller.get();
    }
    @After public void tearDown() { controller.destroy(); }

    @Test public void selectingSizeUpdatesSettingAndRecreatedScreenKeepsIt() {
        row("cache_size").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getListView().performItemClick(null, 5, 5);
        assertEquals(2048, new SongCache(context).limitMb());
        assertFalse(dialog.isShowing());
        controller.destroy();
        controller = Robolectric.buildActivity(SettingsActivity.class).create();
        activity = controller.get();
        assertEquals("2.0 GB", ((TextView) ((android.view.ViewGroup) row("cache_size")).getChildAt(1)).getText().toString());
    }

    @Test public void customSizeValidationKeepsDialogOpenAndCancelDoesNotChangeLimit() {
        row("cache_size").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = dialog.getWindow().getDecorView().findViewWithTag("cache_custom_mb");
        input.setText("999999999999999999999");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertNotNull(input.getError());
        assertEquals(512, new SongCache(context).limitMb());
        input.setText("768");
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals(768, new SongCache(context).limitMb());
        row("cache_size").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        assertEquals(768, new SongCache(context).limitMb());
    }

    @Test public void clearingCacheAsksBeforeRemovingSongs() throws Exception {
        java.io.File directory = new java.io.File(context.getCacheDir(), "tracks");
        java.io.File cached = new java.io.File(directory, "clear-me.track");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(cached)) { output.write(1); }
        row("cache_usage").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(cached.isFile());
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        assertTrue(cached.isFile());
        row("cache_usage").performClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        for (int i = 0; i < 200 && cached.exists(); i++) Thread.sleep(10);
        assertFalse(cached.exists());
    }

    private View row(String tag) { return activity.findViewById(android.R.id.content).findViewWithTag(tag); }
}
