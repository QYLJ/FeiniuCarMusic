package com.feiniu.carmusic.ui;
import android.content.Context;
import android.content.res.Configuration;
@android.annotation.TargetApi(17)
final class ThemeContextApi17 {
    static Context create(Context base, Configuration configuration) { return base.createConfigurationContext(configuration); }
}
