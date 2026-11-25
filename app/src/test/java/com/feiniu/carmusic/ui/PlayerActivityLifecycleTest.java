package com.feiniu.carmusic.ui;

import android.content.ComponentName;
import android.content.ServiceConnection;
import com.feiniu.carmusic.player.PlaybackService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerActivityLifecycleTest {
    @Test public void lateServiceAndTimerCallbacksAfterDestroyDoNotRestartUiOrSubmitLyrics() throws Exception {
        ServiceController<PlaybackService> serviceController = Robolectric.buildService(PlaybackService.class).create();
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
                new ComponentName(RuntimeEnvironment.getApplication(), PlaybackService.class), serviceController.get().onBind(null));
        ActivityController<PlayerActivity> activityController = Robolectric.buildActivity(PlayerActivity.class).create().start();
        PlayerActivity activity = activityController.get();
        try {
            ServiceConnection connection = (ServiceConnection) field("connection").get(activity);
            Runnable tick = (Runnable) field("update").get(activity);
            activityController.stop().destroy();
            connection.onServiceConnected(new ComponentName(activity, PlaybackService.class), serviceController.get().onBind(null));
            tick.run();
            Method lyrics = PlayerActivity.class.getDeclaredMethod("loadLyrics", String.class); lyrics.setAccessible(true);
            lyrics.invoke(activity, "late-song");
            assertNull(field("playback").get(activity));
            assertTrue(((ExecutorService) field("executor").get(activity)).isShutdown());
            assertEquals("", field("displayedGuid").get(activity));
        } finally { serviceController.destroy(); }
    }

    private static Field field(String name) throws Exception {
        Field field = PlayerActivity.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
