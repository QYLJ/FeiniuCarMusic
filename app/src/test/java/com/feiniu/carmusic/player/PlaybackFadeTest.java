package com.feiniu.carmusic.player;
import android.content.Context;
import android.media.*;
import android.os.Looper;
import com.feiniu.carmusic.data.*;
import com.feiniu.carmusic.model.Track;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowMediaPlayer;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaybackFadeTest {
    private ServiceController<PlaybackService> controller;
    private PlaybackService service;
    private MediaPlayer player;
    @Before public void before() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("playback_settings",0).edit().clear().commit();
        context.getSharedPreferences("playback_queue",0).edit().clear().commit();
        new PlaybackPreferences(context).setPreloadCount(0);
        new PlaybackPreferences(context).setPauseForNavigation(false);
        FnApiClient.get(context).session().saveLogin("http://127.0.0.1:1","token","user","127.0.0.1");
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(60000,0));
        TrackFileCache cache=new TrackFileCache(context);
        List<Track> tracks=new ArrayList<Track>();
        for(int i=0;i<3;i++) {
            Track track=new Track();track.guid="fade-"+i;track.codec="mp3";track.durationMs=60000;tracks.add(track);
            TrackFileCache.StreamCache out=cache.cacheOriginalStream(track);
            try{out.write(new byte[]{1,2,3,4},4);out.finish(4);}finally{out.close();}
        }
        controller=Robolectric.buildService(PlaybackService.class).create();service=controller.get();
        service.setQueueAndPlay(tracks,0);awaitPlaying();
        Field field=PlaybackService.class.getDeclaredField("player");field.setAccessible(true);player=(MediaPlayer)field.get(service);
    }
    @After public void after(){controller.destroy();}
    @Test public void fadeInMultipliesNavigationDuckingRatherThanOverridingIt() {
        assertEquals(0f,shadowOf(player).getLeftVolume(),.02f);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(.5f,shadowOf(player).getLeftVolume(),.05f);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
        assertEquals(.125f,shadowOf(player).getLeftVolume(),.02f);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(.25f,shadowOf(player).getLeftVolume(),.01f);
        service.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN);
        assertEquals(1f,shadowOf(player).getLeftVolume(),.01f);
    }
    @Test public void rapidNextKeepsLastRequestedSongAndPauseCancelsPendingTransition() throws Exception {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        service.next();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        assertEquals(0,service.snapshot().queueIndex);
        service.next();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));awaitPlaying();
        assertEquals(2,service.snapshot().queueIndex);
        service.playAt(0);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));awaitPlaying();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        service.next();service.pause();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(0,service.snapshot().queueIndex);assertFalse(service.snapshot().playing);
    }
    private void awaitPlaying() throws Exception {
        for(int i=0;i<200&&!service.snapshot().playing;i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertTrue(service.snapshot().playing);
    }
}
