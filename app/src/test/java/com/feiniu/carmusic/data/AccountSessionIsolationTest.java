package com.feiniu.carmusic.data;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
import java.util.concurrent.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AccountSessionIsolationTest {
    @Test public void oldRequestKeepsOldCredentialsAndCannotInvalidateNewLogin() throws Exception {
        MockWebServer oldNas=new MockWebServer();oldNas.start();
        try {
            FnApiClient root=FnApiClient.get(RuntimeEnvironment.getApplication());
            root.session().saveLogin(oldNas.url("/").toString().replaceAll("/$",""),"old-token","alice","oldnas");
            FnApiClient old=root.snapshot();
            root.session().saveLogin("http://new.example:5666","new-token","bob","newnas");
            oldNas.enqueue(new MockResponse().setResponseCode(401));
            try { old.getTracks(1,1,"");fail(); } catch(ApiException expected){assertEquals(401,expected.getStatusCode());}
            assertEquals("music-token=old-token",oldNas.takeRequest().getHeader("Cookie"));
            assertEquals("bob",root.session().getUsername());assertEquals("new-token",root.session().getToken());
        } finally {oldNas.shutdown();}
    }
    @Test public void successfulAuthenticationDoesNotSwitchSessionBeforeUiStopsOldPlayback() throws Exception {
        MockWebServer nas=new MockWebServer();nas.start();
        try {
            FnApiClient root=FnApiClient.get(RuntimeEnvironment.getApplication());
            root.session().saveLogin("http://old.example","old-token","alice","oldnas");
            nas.enqueue(new MockResponse().setBody("{\"code\":0,\"data\":{\"userToken\":\"new-token\"}}"));
            FnApiClient.LoginResult candidate=root.authenticate(nas.url("/").toString(),"bob","password");
            assertEquals("new-token",candidate.token);assertEquals("alice",root.session().getUsername());
            assertFalse(String.valueOf(nas.takeRequest().getHeader("Cookie")).contains("old-token"));
        } finally {nas.shutdown();}
    }

    @Test public void inFlightNormalApiRequestCannotLogOutAccountSelectedWhileItWasWaiting() throws Exception {
        MockWebServer nas = new MockWebServer();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        nas.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                entered.countDown(); release.await(5, TimeUnit.SECONDS);
                return new MockResponse().setResponseCode(401);
            }
        });
        nas.start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            FnApiClient root = FnApiClient.get(RuntimeEnvironment.getApplication());
            root.session().saveLogin(nas.url("/").toString(), "old-token", "alice", "oldnas");
            Future<?> old = worker.submit(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    try { root.getTracks(1, 1, ""); fail("Expected old session to expire"); }
                    catch (ApiException expected) { assertEquals(401, expected.getStatusCode()); }
                    return null;
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            root.session().saveLogin("http://new.example", "new-token", "bob", "newnas");
            release.countDown(); old.get(5, TimeUnit.SECONDS);
            assertEquals("new-token", root.session().getToken());
            assertEquals("bob", root.session().getUsername());
        } finally { release.countDown(); worker.shutdownNow(); nas.shutdown(); }
    }
}
