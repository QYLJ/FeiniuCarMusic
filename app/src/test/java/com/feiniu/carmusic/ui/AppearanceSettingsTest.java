package com.feiniu.carmusic.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.feiniu.carmusic.data.UiPreferences;
import com.feiniu.carmusic.player.AudioCompatibility;
import com.feiniu.carmusic.player.PlaybackService;
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
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AppearanceSettingsTest {
    private Context context;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ui_preferences", 0).edit().clear().commit();
        context.getSharedPreferences("playback_settings", 0).edit().clear().commit();
        context.getSharedPreferences("playback_queue", 0).edit().clear().commit();
    }

    @Test public void navigationDefaultsPersistAndEmptySelectionDoesNotEraseThem() {
        UiPreferences preferences = new UiPreferences(context);
        for (boolean visible : preferences.visibleNavigation()) assertTrue(visible);
        boolean[] selection = {false, true, false, false, false, false, true, false};
        assertTrue(preferences.saveNavigation(selection));
        assertArrayEquals(selection, new UiPreferences(context).visibleNavigation());
        assertFalse(preferences.saveNavigation(new boolean[8]));
        assertArrayEquals(selection, new UiPreferences(context).visibleNavigation());
    }

    @Test public void autoSizingUsesDpSoHigherDensityDoesNotArtificiallyEnlargeFonts() {
        UiPreferences preferences = new UiPreferences(context);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.widthPixels = 1280; metrics.heightPixels = 720; metrics.density = 2;
        assertEquals(1f, preferences.fontMultiplier(metrics), .001f);
        metrics.density = 1;
        assertEquals(1.1f, preferences.fontMultiplier(metrics), .001f);
        preferences.setFontMode(3);
        assertEquals(1.3f, new UiPreferences(context).fontMultiplier(metrics), .001f);
    }

    @Test public void repeatedResumeDoesNotCompoundFontScalingAndStandardRestoresOriginalSize() {
        TextView text = new TextView(context);
        text.setTextSize(16);
        float standard = text.getTextSize();
        new UiPreferences(context).setFontMode(3);
        UiAppearance.apply(text);
        UiAppearance.apply(text);
        assertEquals(standard * 1.3f, text.getTextSize(), .01f);
        new UiPreferences(context).setFontMode(1);
        UiAppearance.apply(text);
        assertEquals(standard, text.getTextSize(), .01f);
    }

    @Test public void fontChoicePreviewsImmediatelyWithoutRecreatingSettings() {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).create();
        try {
            SettingsActivity activity = controller.get();
            LinearLayout row = activity.findViewById(android.R.id.content).findViewWithTag("font");
            TextView label = (TextView) row.getChildAt(0);
            float before = label.getTextSize();
            row.performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getListView().performItemClick(null, 3, 3);
            assertEquals(3, new UiPreferences(context).fontMode());
            assertEquals(before * 1.3f, label.getTextSize(), .01f);
            assertFalse(dialog.isShowing());
        } finally { controller.destroy(); }
    }

    @Test public void sidebarDialogCancellationAndSaveHaveDistinctEffects() {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).create();
        try {
            View row = controller.get().findViewById(android.R.id.content).findViewWithTag("navigation");
            row.performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getListView().setItemChecked(0, true);
            dialog.getListView().performItemClick(null, 0, 0);
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            assertTrue(new UiPreferences(context).visibleNavigation()[0]);
            row.performClick();
            dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getListView().setItemChecked(0, true);
            dialog.getListView().performItemClick(null, 0, 0);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            assertFalse(new UiPreferences(context).visibleNavigation()[0]);
        } finally { controller.destroy(); }
    }

    @Test public void quickShortcutAndSettingsSharePersistedPlaybackChoices() {
        ActivityController<SettingsActivity> activity = Robolectric.buildActivity(SettingsActivity.class).create();
        ServiceController<PlaybackService> service = Robolectric.buildService(PlaybackService.class).create();
        try {
            AudioSettingsDialogs.showModes(activity.get(), service.get(), null, true);
            ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 2, 2);
            assertEquals(AudioCompatibility.NAS_MP3, service.get().getAudioMode());
            AudioSettingsDialogs.showBitrates(activity.get(), service.get(), null);
            ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 3, 3);
            assertEquals(320, service.get().getMp3BitrateKbps());
            service.destroy();
            service = Robolectric.buildService(PlaybackService.class).create();
            assertEquals(AudioCompatibility.NAS_MP3, service.get().getAudioMode());
            assertEquals(320, service.get().getMp3BitrateKbps());
        } finally { service.destroy(); activity.destroy(); }
    }

    @Test public void qualityBadgeOmitsTechnicalDetailsButDistinguishesActualTranscoding() {
        assertEquals("MP3 · 转码", AudioSettingsDialogs.compactQuality("NAS 转码 · MP3 320 kbps / 44.1kHz / 2声道"));
        assertEquals("FLAC · 原音", AudioSettingsDialogs.compactQuality("原始 · FLAC 24bit / 96.0kHz / 2声道"));
        assertEquals("音质", AudioSettingsDialogs.compactQuality(""));
    }
}
