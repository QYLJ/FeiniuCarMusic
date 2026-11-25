package com.feiniu.carmusic;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.feiniu.carmusic.diagnostics.AppLog;

/** Initializes private diagnostics before any screen or playback service starts. */
public final class CarMusicApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.initialize(this);
        AppLog.info("app.start", "process created");
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                AppLog.info("screen.created", activity.getClass().getSimpleName());
            }
            @Override public void onActivityResumed(Activity activity) {
                AppLog.info("screen.resumed", activity.getClass().getSimpleName());
            }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    @Override public void onLowMemory() {
        com.feiniu.carmusic.data.CoverLoader.trimMemory(80);
        AppLog.warn("app.low_memory", "system requested memory release");
        super.onLowMemory();
    }

    @Override public void onTrimMemory(int level) {
        com.feiniu.carmusic.data.CoverLoader.trimMemory(level);
        AppLog.info("app.trim_memory", "level=" + level);
        super.onTrimMemory(level);
    }
}
