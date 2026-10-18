package com.feiniu.carmusic.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

/** Device-local presentation preferences, independent of the NAS account. */
public final class UiPreferences {
    public static final String[] NAV_KEYS = {"home", "favorites", "recent", "albums",
            "artists", "genres", "library", "playlists"};
    public static final String[] NAV_LABELS = {"首页", "收藏", "最近播放", "专辑",
            "歌手", "音乐风格", "音乐库", "歌单"};
    public static final String[] FONT_LABELS = {"自动适配（推荐）", "标准", "大", "特大"};
    private final SharedPreferences prefs;

    public UiPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("ui_preferences", Context.MODE_PRIVATE);
    }

    public boolean[] visibleNavigation() {
        boolean[] visible = new boolean[NAV_KEYS.length];
        for (int i = 0; i < visible.length; i++) visible[i] = prefs.getBoolean("nav_" + NAV_KEYS[i], true);
        return visible;
    }

    public boolean saveNavigation(boolean[] visible) {
        if (visible == null || visible.length != NAV_KEYS.length) return false;
        int count = 0;
        for (boolean value : visible) if (value) count++;
        if (count == 0) return false;
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < visible.length; i++) editor.putBoolean("nav_" + NAV_KEYS[i], visible[i]);
        editor.apply();
        return true;
    }

    public int fontMode() {
        int value = prefs.getInt("font_mode", 0);
        return value >= 0 && value < FONT_LABELS.length ? value : 0;
    }

    public void setFontMode(int value) {
        if (value >= 0 && value < FONT_LABELS.length) prefs.edit().putInt("font_mode", value).apply();
    }

    public float fontMultiplier(DisplayMetrics metrics) {
        switch (fontMode()) {
            case 1: return 1f;
            case 2: return 1.15f;
            case 3: return 1.3f;
            default:
                // Use logical size rather than pixels: dense screens must not look like giant screens.
                float shortSide = Math.min(metrics.widthPixels, metrics.heightPixels) / metrics.density;
                return shortSide >= 600f ? 1.1f : 1f;
        }
    }
}
