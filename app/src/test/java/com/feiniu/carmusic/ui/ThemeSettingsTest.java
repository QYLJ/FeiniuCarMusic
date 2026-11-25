package com.feiniu.carmusic.ui;
import android.content.Context;
import android.content.res.Configuration;
import com.feiniu.carmusic.R;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={21,28})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ThemeSettingsTest {
    @Test public void lightAndDarkUseMatchingResourcePalettesAndDoNotChangeSystemMode() {
        Context context=RuntimeEnvironment.getApplication();
        int original=context.getResources().getConfiguration().uiMode;
        ThemeSettings.setMode(context,1);
        ActivityController<SettingsActivity> light=Robolectric.buildActivity(SettingsActivity.class).create();
        try { assertEquals(0xfff4f5f8,light.get().getResources().getColor(R.color.background));
            assertEquals(0xff1e2433,light.get().getResources().getColor(R.color.text_primary)); }
        finally {light.destroy();}
        assertEquals(original,context.getResources().getConfiguration().uiMode);
        ThemeSettings.setMode(context,2);
        ActivityController<SettingsActivity> dark=Robolectric.buildActivity(SettingsActivity.class).create();
        try {assertEquals(0xff121118,dark.get().getResources().getColor(R.color.background));
            assertEquals(0xfffaf9fc,dark.get().getResources().getColor(R.color.text_primary));}
        finally {dark.destroy();}
    }
    @Test public void followCarUsesDayNightConfigurationAndManualChoiceOverridesIt() {
        Context context=RuntimeEnvironment.getApplication();ThemeSettings.setMode(context,0);
        Configuration config=new Configuration(context.getResources().getConfiguration());
        config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_NO;
        context.getResources().updateConfiguration(config,context.getResources().getDisplayMetrics());
        assertFalse(ThemeSettings.isNight(context));
        config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|Configuration.UI_MODE_NIGHT_YES;
        context.getResources().updateConfiguration(config,context.getResources().getDisplayMetrics());
        assertTrue(ThemeSettings.isNight(context));
        ThemeSettings.setMode(context,1);assertFalse(ThemeSettings.isNight(context));
    }
}
