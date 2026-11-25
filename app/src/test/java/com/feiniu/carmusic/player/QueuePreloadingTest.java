package com.feiniu.carmusic.player;
import android.content.Context;
import android.os.Looper;
import com.feiniu.carmusic.data.*;
import com.feiniu.carmusic.model.Track;
import okhttp3.mockwebserver.*;
import okio.Buffer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowMediaPlayer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueuePreloadingTest {
    @Test public void selectingInFlightNextSongReusesDownloadAndPreparesFollowingSong() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        context.getSharedPreferences("playback_settings",0).edit().clear().commit();
        context.getSharedPreferences("playback_queue",0).edit().clear().commit();
        new PlaybackPreferences(context).setFadeMs(0);
        MockWebServer nas=new MockWebServer();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger secondRequests=new AtomicInteger(),thirdRequests=new AtomicInteger();
        nas.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request)throws InterruptedException{
            String guid=request.getRequestUrl().queryParameter("guid");
            if("preload-1".equals(guid)){secondRequests.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);}
            if("preload-2".equals(guid))thirdRequests.incrementAndGet();
            return new MockResponse().setBody(new Buffer().write(new byte[]{1,2,3,4}));
        }});nas.start();
        FnApiClient.get(context).session().saveLogin(nas.url("/").toString().replaceAll("/$",""),"token","user","127.0.0.1");
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(60000,0));
        List<Track> tracks=new ArrayList<Track>();
        for(int i=0;i<3;i++){Track t=new Track();t.guid="preload-"+i;t.codec="mp3";t.durationMs=60000;tracks.add(t);}
        TrackFileCache cache=new TrackFileCache(context);TrackFileCache.StreamCache out=cache.cacheOriginalStream(tracks.get(0));
        try{out.write(new byte[]{1,2,3,4},4);out.finish(4);}finally{out.close();}
        ServiceController<PlaybackService> controller=Robolectric.buildService(PlaybackService.class).create();
        try{
            PlaybackService service=controller.get();service.setQueueAndPlay(tracks,0);awaitIndex(service,0);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            service.next();assertEquals(1,service.snapshot().queueIndex);release.countDown();awaitIndex(service,1);
            assertEquals(1,secondRequests.get());
            for(int i=0;i<200&&cache.cachedOriginal(tracks.get(2))==null;i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
            assertNotNull(cache.cachedOriginal(tracks.get(2)));
            service.next();awaitIndex(service,2);assertEquals(1,thirdRequests.get());
            assertEquals(2,nas.getRequestCount());
        }finally{release.countDown();controller.destroy();nas.shutdown();}
    }
    private void awaitIndex(PlaybackService service,int index)throws Exception{
        for(int i=0;i<300&&(!service.snapshot().playing||service.snapshot().queueIndex!=index);i++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10);}
        assertEquals(index,service.snapshot().queueIndex);assertTrue(service.snapshot().playing);
    }
}
