package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import com.feiniu.carmusic.player.PlaybackService;

/** The player shortcut and settings screen edit the same service-backed preferences. */
final class AudioSettingsDialogs {
    static void showModes(final Activity activity, final PlaybackService playback,
                          final Runnable changed, boolean showSettingsLink) {
        if (playback == null) return;
        final String[] labels = {"自动兼容（推荐）", "原始音质", "NAS 转码 MP3", "NAS 转码 FLAC"};
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("播放音质")
                .setSingleChoiceItems(labels, playback.getAudioMode(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playback.setAudioMode(which);
                        dialog.dismiss();
                        if (changed != null) changed.run();
                    }
                }).setPositiveButton("MP3 码率", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showBitrates(activity, playback, changed);
                    }
                }).setNegativeButton("取消", null);
        if (showSettingsLink) builder.setNeutralButton("更多设置", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                activity.startActivity(new Intent(activity, SettingsActivity.class));
            }
        });
        show(builder);
    }

    static void showBitrates(final Activity activity, final PlaybackService playback, final Runnable changed) {
        if (playback == null) return;
        final int[] rates = {128, 192, 256, 320};
        String[] labels = {"128 kbps · 省流量", "192 kbps · 默认", "256 kbps", "320 kbps · 高码率"};
        int checked = 1;
        for (int i = 0; i < rates.length; i++) if (rates[i] == playback.getMp3BitrateKbps()) checked = i;
        show(new AlertDialog.Builder(activity).setTitle("MP3 转码码率")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playback.setMp3BitrateKbps(rates[which]);
                        dialog.dismiss();
                        if (changed != null) changed.run();
                    }
                }).setNegativeButton("取消", null));
    }

    static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        UiAppearance.apply(dialog.getWindow().getDecorView());
        if (dialog.getListView() != null) dialog.getListView().setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(android.view.View parent, android.view.View child) { UiAppearance.apply(child); }
            @Override public void onChildViewRemoved(android.view.View parent, android.view.View child) { }
        });
        return dialog;
    }

    static String compactQuality(String description) {
        if (description == null || description.length() == 0) return "音质";
        int separator = description.indexOf('·');
        if (separator < 0) return "原音";
        String detail = description.substring(separator + 1).trim();
        int end = detail.indexOf(' ');
        String codec = end < 0 ? detail : detail.substring(0, end);
        if (codec.length() == 0 || codec.length() > 6) codec = "音频";
        return codec + (description.startsWith("NAS 转码") ? " · 转码" : " · 原音");
    }
}
