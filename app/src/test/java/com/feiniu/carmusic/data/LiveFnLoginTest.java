package com.feiniu.carmusic.data;

import com.feiniu.carmusic.model.TrackPage;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Constructor;

import static org.junit.Assert.*;

/** Opt-in live login. Credentials are supplied only through the test process environment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LiveFnLoginTest {
    @Test public void fnIdLoginLoadsLibraryAndReconnectsWithSavedMusicSession() throws Exception {
        String server = System.getenv("FEINIU_TEST_SERVER");
        String username = System.getenv("FEINIU_TEST_USERNAME");
        String password = System.getenv("FEINIU_TEST_PASSWORD");
        Assume.assumeTrue(server != null && username != null && password != null);
        RuntimeEnvironment.getApplication().getSharedPreferences("fn_session", 0).edit().clear().commit();
        try {
            FnApiClient api = newClient();
            api.login(server, username, password);
            assertTrue(api.session().hasSession());
            assertEquals(server, api.session().getLoginAddress());
            TrackPage first = api.getTracks(1, 2, "");
            assertTrue(first.total > 0);
            assertFalse(first.tracks.isEmpty());
            // A new client has no in-memory gateway cookies, as after an app process restart.
            FnApiClient restarted = newClient();
            assertEquals(first.total, restarted.getTracks(1, 2, "").total);
        } finally {
            RuntimeEnvironment.getApplication().getSharedPreferences("fn_session", 0).edit().clear().commit();
        }
    }

    private FnApiClient newClient() throws Exception {
        Constructor<FnApiClient> constructor = FnApiClient.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RuntimeEnvironment.getApplication());
    }
}
