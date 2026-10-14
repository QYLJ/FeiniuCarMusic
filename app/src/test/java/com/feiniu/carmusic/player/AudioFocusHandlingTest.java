package com.feiniu.carmusic.player;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import com.feiniu.carmusic.model.Track;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {21, 25, 28, 35})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AudioFocusHandlingTest {
    private ServiceController<PlaybackService> controller;
    private PlaybackService service;
    private AudioManager audio;
    @Before public void setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("playback_queue", 0).edit().clear().commit();
        controller = Robolectric.buildService(PlaybackService.class).create(); service = controller.get();
        audio = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
        Track track = new Track(); track.guid = "focus-only"; track.accessStatus = 3;
        service.setQueueAndPlay(Collections.singletonList(track), 0);
    }
    @After public void tearDown() { controller.destroy(); }
    @Test public void synchronousFocusGrantRestoresDuckedVolume() throws Exception {
        MediaPlayer player = (MediaPlayer) field("player").get(service);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(0.25f, shadowOf(player).getLeftVolume(), 0.0f);
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        assertTrue(request());
        assertEquals(1.0f, shadowOf(player).getLeftVolume(), 0.0f);
        assertEquals(1.0f, shadowOf(player).getRightVolume(), 0.0f);
    }
    @Test public void deniedFocusProducesVisibleCauseAndZeroSystemVolumeIsNotOverridden() throws Exception {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        assertFalse(request());
        assertTrue(service.snapshot().error.contains("音频焦点"));
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertTrue(service.snapshot().audioWarning.contains("音量为 0"));
    }
    @Test public void temporaryLossDuringLoadingPreservesResumeIntentAndManualPauseCancelsIt() throws Exception {
        field("loading").setBoolean(service, true); field("playWhenPrepared").setBoolean(service, true);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertTrue(field("resumeOnFocusGain").getBoolean(service));
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(field("playWhenPrepared").getBoolean(service));
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.pause();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(field("playWhenPrepared").getBoolean(service));
    }
    private boolean request() throws Exception {
        Method method = PlaybackService.class.getDeclaredMethod("requestAudioFocus"); method.setAccessible(true);
        return (Boolean) method.invoke(service);
    }
    private Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
