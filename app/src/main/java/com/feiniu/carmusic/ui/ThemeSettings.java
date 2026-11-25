package com.feiniu.carmusic.ui;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.LayoutInflater;

public final class ThemeSettings {
    public static final String[] LABELS = {"跟随车机（推荐）", "明亮主题", "暗色主题"};
    public static int mode(Context context) {
        int value = context.getApplicationContext().getSharedPreferences("appearance", 0).getInt("theme_mode", 0);
        return value >= 0 && value <= 2 ? value : 0;
    }
    public static void setMode(Context context, int mode) {
        if (mode >= 0 && mode <= 2) context.getApplicationContext().getSharedPreferences("appearance", 0).edit().putInt("theme_mode", mode).apply();
    }
    public static boolean isNight(Context context) {
        int mode = mode(context);
        if (mode != 0) return mode == 2;
        int system = context.getApplicationContext().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return system != Configuration.UI_MODE_NIGHT_NO;
    }
    static Context wrap(final Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (isNight(base) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        if (android.os.Build.VERSION.SDK_INT >= 17) return ThemeContextApi17.create(base, config);
        final Resources resources = new Resources(base.getAssets(), base.getResources().getDisplayMetrics(), config);
        return new ContextWrapper(base) {
            private LayoutInflater inflater;
            @Override public Resources getResources() { return resources; }
            @Override public Object getSystemService(String name) {
                if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
                    if (inflater == null) inflater = LayoutInflater.from(base).cloneInContext(this);
                    return inflater;
                }
                return super.getSystemService(name);
            }
        };
    }
}
