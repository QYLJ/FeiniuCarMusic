package com.feiniu.carmusic.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowNetworkInfo;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class NetworkRouteRecoveryTest {
    private MockWebServer server;
    private FnApiClient api;
    private Context context;
    private String origin;
    private AtomicInteger lookups;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("fn_session", 0).edit().clear().commit();
        Constructor<FnApiClient> constructor = FnApiClient.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        api = constructor.newInstance(context);
        server = new MockWebServer(); server.start();
        origin = server.url("/").toString().replaceAll("/$", "");
        lookups = new AtomicInteger();
        Field lookup = FnApiClient.class.getDeclaredField("routeLookup"); lookup.setAccessible(true);
        lookup.set(api, (FnApiClient.RouteLookup) fnId -> {
            assertEquals("testnas", fnId); lookups.incrementAndGet();
            return Arrays.asList("http://192.168.0.108:5666", origin);
        });
    }
    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void mobileSkipsStaleLanRouteAndReadsLibraryThroughNewRouteWithExistingToken() throws Exception {
        mobile();
        api.session().saveLogin("http://192.168.0.108:5666", "retained-token", "music-user", "testnas");
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{}}"));
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"total\":0,\"list\":[]}}"));
        assertEquals(0, api.getTracks(1, 500, "").total);
        assertEquals(origin, api.session().getBaseUrl());
        assertEquals("testnas", api.session().getLoginAddress());
        assertEquals("retained-token", api.session().getToken());
        assertNull(server.takeRequest().getHeader("Cookie"));
        assertEquals("music-token=retained-token", server.takeRequest().getHeader("Cookie"));
        assertEquals(1, lookups.get());
    }

    @Test public void mobileManualLanAddressGetsActionableErrorWithoutTimeoutOrDroppingLogin() throws Exception {
        mobile();
        api.session().saveLogin("http://192.168.0.108:5666", "retained-token", "music-user", "192.168.0.108");
        try { api.getTracks(1, 500, ""); fail(); }
        catch (ApiException error) { assertTrue(error.getMessage().contains("FN ID")); }
        assertEquals(0, server.getRequestCount());
        assertEquals(0, lookups.get());
        assertTrue(api.session().hasSession());
    }

    @Test public void connectionFailureReResolvesFnIdAndRetriesReadOnce() throws Exception {
        MockWebServer dead = new MockWebServer(); dead.start();
        String deadOrigin = dead.url("/").toString().replaceAll("/$", ""); dead.shutdown();
        api.session().saveLogin(deadOrigin, "retained-token", "music-user", "testnas");
        // A disconnected network is neither mobile nor a reason to probe the private candidate.
        Field lookup = FnApiClient.class.getDeclaredField("routeLookup"); lookup.setAccessible(true);
        lookup.set(api, (FnApiClient.RouteLookup) fnId -> { lookups.incrementAndGet(); return Arrays.asList(origin); });
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{}}"));
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"total\":0,\"list\":[]}}"));
        api.getTracks(1, 500, "");
        assertEquals(origin, api.session().getBaseUrl());
        assertEquals(1, lookups.get());
        assertEquals(2, server.getRequestCount());
    }

    @Test public void authenticationFailureDoesNotTryOtherRoutes() throws Exception {
        api.session().saveLogin(origin, "expired-token", "music-user", "testnas");
        server.enqueue(new MockResponse().setResponseCode(401));
        try { api.getTracks(1, 500, ""); fail(); }
        catch (ApiException error) { assertEquals(401, error.getStatusCode()); }
        assertEquals(0, lookups.get());
        assertFalse(api.session().hasSession());
    }

    @Test public void privateAddressDetectionDoesNotTreatPublic172OrDomainNamesAsLan() {
        assertTrue(FnApiClient.isLocalAddress("https://172.20.1.1:5667"));
        assertTrue(FnApiClient.isLocalAddress("http://[fd12::1]:5666"));
        assertFalse(FnApiClient.isLocalAddress("http://172.32.1.1:5666"));
        assertFalse(FnApiClient.isLocalAddress("https://fdmusic.example"));
        assertFalse(FnApiClient.isLocalAddress("https://10.music.example"));
    }

    private void mobile() {
        shadowOf((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).setActiveNetworkInfo(
                ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_MOBILE, 13, true, true));
    }
}
