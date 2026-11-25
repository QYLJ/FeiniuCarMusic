package com.feiniu.carmusic.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class PlaybackPreferences {
    private final SharedPreferences prefs;
    public PlaybackPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("playback_settings", 0);
    }
    public int fadeMs() {
        int value = prefs.getInt("fade_ms", 500);
        return value >= 0 && value <= 2000 ? value : 500;
    }
    public boolean autoPlayOnLaunch() { return prefs.getBoolean("auto_play_on_launch", false); }
    public void setAutoPlayOnLaunch(boolean enabled) { prefs.edit().putBoolean("auto_play_on_launch", enabled).apply(); }
    public boolean pauseForNavigation() { return prefs.getBoolean("pause_for_navigation", true); }
    public void setPauseForNavigation(boolean value) { prefs.edit().putBoolean("pause_for_navigation", value).apply(); }
    public void setFadeMs(int value) { if (value >= 0 && value <= 2000) prefs.edit().putInt("fade_ms", value).apply(); }
    public int preloadCount() {
        int value = prefs.getInt("preload_count", 2);
        return value >= 0 && value <= 3 ? value : 2;
    }
    public void setPreloadCount(int value) { if (value >= 0 && value <= 3) prefs.edit().putInt("preload_count", value).apply(); }
}
