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
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;
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
        RuntimeEnvironment.getApplication().getSharedPreferences("playback_settings", 0).edit().clear().commit();
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
    @Test public void repeatedTemporaryLossDoesNotForgetInterruptedLoading() throws Exception {
        field("loading").setBoolean(service, true);
        field("playWhenPrepared").setBoolean(service, true);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertTrue("Duplicate loss must not erase pending automatic resume", field("resumeOnFocusGain").getBoolean(service));
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(field("playWhenPrepared").getBoolean(service));
    }
    @Test public void regainedFocusResumesWithoutAnotherFocusRequest() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertFalse(player.isPlaying());
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue("GAIN already grants focus; resume must not make a second request", player.isPlaying());
        assertEquals(12000, service.snapshot().positionMs);
    }
    @Test public void navigationDuckingContinuesAndTrackPreparationDoesNotStealFocus() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertTrue(player.isPlaying());
        assertEquals(.25f, shadowOf(player).getLeftVolume(), .001f);
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        assertTrue("A ducked player may continue without reclaiming navigation focus", request());
        assertEquals(.25f, shadowOf(player).getLeftVolume(), .001f);
        service.onPrepared(player);
        assertTrue(player.isPlaying());
        assertEquals(.25f, shadowOf(player).getLeftVolume(), .001f);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(1f, shadowOf(player).getLeftVolume(), .001f);
    }
    @Test public void preparedWhileInterruptedWaitsForGainEvenAfterAnotherLoss() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        field("prepared").setBoolean(service, false);
        field("loading").setBoolean(service, true);
        shadowOf(player).setState(ShadowMediaPlayer.State.PREPARED);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onPrepared(player);
        assertFalse(player.isPlaying());
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertTrue(field("resumeOnFocusGain").getBoolean(service));
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(player.isPlaying());
    }
    @Test public void manualPauseDuringInterruptionCancelsResumeAndStaleGain() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.pause();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(player.isPlaying());
        assertFalse(field("resumeOnFocusGain").getBoolean(service));
        assertFalse(field("focusRequestActive").getBoolean(service));
    }
    @Test public void permanentLossDoesNotAutomaticallyRestartAfterAStaleGain() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(player.isPlaying());
        assertFalse(field("resumeOnFocusGain").getBoolean(service));
        assertFalse(field("focusRequestActive").getBoolean(service));
        assertTrue(service.getAudioDiagnostics().contains("永久失焦 (-1)"));
        service.play();
        assertTrue("Explicit play may acquire focus after permanent loss", player.isPlaying());
    }
    @Test public void weakerDuckEventCannotResumeAnAudioStreamPausedForTransientLoss() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertFalse(request());
        assertFalse(player.isPlaying());
        assertTrue(field("resumeOnFocusGain").getBoolean(service));
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertTrue(player.isPlaying());
        assertEquals(1f, shadowOf(player).getLeftVolume(), .001f);
    }
    @Test public void alreadyPausedMusicDoesNotStartWhenNavigationEnds() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.pause();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(player.isPlaying());
    }
    @Test public void stoppingQueueDuringNavigationCancelsAutomaticResume() throws Exception {
        MediaPlayer player = startPreparedPlayer();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        service.clearQueueAndStop();
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertFalse(service.snapshot().playing);
        assertNull(service.snapshot().track);
        assertFalse(field("resumeOnFocusGain").getBoolean(service));
    }
    @Test public void diagnosticsKeepOnlyRecentFocusEventsAndDistinguishPauseCommands() throws Exception {
        startPreparedPlayer();
        for (int i = 0; i < 40; i++) service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
        assertEquals(24, ((java.util.List<?>) field("audioEvents").get(service)).size());
        assertTrue(service.getAudioDiagnostics().contains("等待自动恢复：是"));
        service.pause();
        assertTrue(service.getAudioDiagnostics().contains("收到暂停指令"));
        assertTrue(service.getAudioDiagnostics().contains("等待自动恢复：否"));
    }
    private MediaPlayer startPreparedPlayer() throws Exception {
        MediaPlayer player = (MediaPlayer) field("player").get(service);
        DataSource source = DataSource.toDataSource("focus-fixture");
        ShadowMediaPlayer.addMediaInfo(source, new ShadowMediaPlayer.MediaInfo(120000, 0));
        shadowOf(player).doSetDataSource(source);
        shadowOf(player).setState(ShadowMediaPlayer.State.PREPARED);
        field("prepared").setBoolean(service, true);
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        service.play();
        shadowOf(player).setCurrentPosition(12000);
        assertTrue(player.isPlaying());
        return player;
    }
    private boolean request() throws Exception {
        Method method = PlaybackService.class.getDeclaredMethod("requestAudioFocus"); method.setAccessible(true);
        return (Boolean) method.invoke(service);
    }
    private Field field(String name) throws Exception {
        Field field = PlaybackService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
