package com.feiniu.carmusic.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CoverLoaderTest {
    private Context context;
    private MockWebServer server;
    private CoverLoader loader;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Field instance = FnApiClient.class.getDeclaredField("instance");
        instance.setAccessible(true); instance.set(null, null);
        server = new MockWebServer(); server.start();
        FnApiClient.get(context).session().saveLogin(server.url("/").toString().replaceAll("/$", ""),
                "fixture-token", "fixture", "local");
        Constructor<CoverLoader> constructor = CoverLoader.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true); loader = constructor.newInstance(context);
    }

    @After public void tearDown() throws Exception {
        Field executor = CoverLoader.class.getDeclaredField("executor"); executor.setAccessible(true);
        ((ExecutorService) executor.get(loader)).shutdownNow();
        server.shutdown();
    }

    @Test public void playerFetchesLargeCoverAfterLibraryThumbnailAndSharesItWithBackground() throws Exception {
        server.enqueue(image(16, Color.RED));
        ImageView thumbnail = new ImageView(context);
        loader.load("album", 160, thumbnail);
        awaitSize(thumbnail, 16);
        assertTrue(server.takeRequest().getPath().endsWith("coverId=album&size=160"));

        server.enqueue(image(72, Color.BLUE));
        ImageView cover = new ImageView(context), background = new ImageView(context);
        loader.load("album", 720, cover, background);
        awaitSize(cover, 72);
        assertSame(bitmap(cover), bitmap(background));
        assertTrue(server.takeRequest().getPath().endsWith("coverId=album&size=720"));

        ImageView cached = new ImageView(context);
        loader.load("album", 720, cached);
        assertSame(bitmap(cover), bitmap(cached));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void lateThumbnailResponseCannotReplaceLargerCoverRequestedForSameView() throws Exception {
        server.enqueue(image(16, Color.RED).setBodyDelay(350, TimeUnit.MILLISECONDS));
        ImageView target = new ImageView(context);
        loader.load("album", 160, target);
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        server.enqueue(image(72, Color.BLUE));
        loader.load("album", 720, target);
        awaitSize(target, 72);
        Thread.sleep(450);
        ShadowLooper.idleMainLooper();
        assertEquals(72, bitmap(target).getWidth());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void interruptedCoverDownloadIsRetriedBeforePublishingAnImage() throws Exception {
        server.enqueue(image(72, Color.RED).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        server.enqueue(image(72, Color.BLUE));
        ImageView target = new ImageView(context);
        loader.load("interrupted-album", 720, target);
        awaitSize(target, 72);
        assertEquals(2, server.getRequestCount());
    }

    @Test public void oversizedServerImageIsDecodedNearTheRequestedThumbnailSize() throws Exception {
        server.enqueue(image(1024, Color.BLUE));
        Bitmap result = FnApiClient.get(context).downloadCover("large-source", 160);
        assertNotNull(result);
        assertTrue(result.getWidth() >= 160);
        assertTrue(result.getWidth() <= 320);
        assertEquals(1, server.getRequestCount());
    }

    private MockResponse image(int size, int color) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
        bitmap.recycle();
        return new MockResponse().setHeader("Content-Type", "image/png").setBody(new Buffer().write(bytes.toByteArray()));
    }
    private Bitmap bitmap(ImageView target) {
        return target.getDrawable() instanceof BitmapDrawable ? ((BitmapDrawable) target.getDrawable()).getBitmap() : null;
    }
    private void awaitSize(ImageView target, int size) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            if (bitmap(target) != null && bitmap(target).getWidth() == size) return;
            Thread.sleep(10);
        }
        fail("Cover did not reach requested image size " + size);
    }
}
