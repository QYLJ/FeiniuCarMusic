package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import com.feiniu.carmusic.R;

/** Resources are local to this screen, so a manual theme never changes the car's system mode. */
public abstract class ThemeActivity extends Activity {
    private boolean night;
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(ThemeSettings.wrap(base)); }
    @Override protected void onCreate(Bundle state) {
        night = ThemeSettings.isNight(this);
        setTheme(night ? R.style.AppTheme : R.style.AppTheme_Light);
        super.onCreate(state);
    }
    @Override protected void onResume() {
        super.onResume();
        if (night != ThemeSettings.isNight(this)) recreate();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        Configuration themed = new Configuration(config);
        themed.uiMode = (themed.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (ThemeSettings.isNight(this) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        getResources().updateConfiguration(themed, getResources().getDisplayMetrics());
        if (night != ThemeSettings.isNight(this)) recreate();
    }
}
