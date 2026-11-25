package com.feiniu.carmusic.data;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FnLoginTest {
    private MockWebServer server;
    private final Map<String, RequestHandler> routes = new ConcurrentHashMap<String, RequestHandler>();
    private String origin;
    private FnApiClient api;

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                RequestHandler handler = routes.get(request.getRequestUrl().encodedPath());
                return handler == null ? new MockResponse().setResponseCode(404) : handler.handle(request);
            }
        });
        server.start();
        origin = server.url("/").toString().replaceAll("/$", "");
        RuntimeEnvironment.getApplication().getSharedPreferences("fn_session", 0).edit().clear().commit();
        Constructor<FnApiClient> constructor = FnApiClient.class.getDeclaredConstructor(android.content.Context.class);
        constructor.setAccessible(true);
        api = constructor.newInstance(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void protectedConfigStillSelectsReachableMusicRoute() throws Exception {
        respond("/bad/music/api/v1/sys/config", 200, "<html>FN Connect</html>");
        respond("/protected/music/api/v1/sys/config", 401,
                "{\"code\":99999,\"msg\":\"INVALID TOKEN\",\"data\":null}");
        assertEquals(origin + "/protected", findRoute(origin + "/bad", origin + "/protected"));
    }

    @Test public void protectedMusicConfigDoesNotRequireOptionalDataEnvelope() throws Exception {
        respond("/music/api/v1/sys/config", 401, "{\"code\":120001,\"msg\":\"unauthorized\"}");
        assertEquals(origin, findRoute(origin));
    }

    @Test public void businessUnauthorizedConfigStillSelectsMusicRoute() throws Exception {
        respond("/music/api/v1/sys/config", 200,
                "{\"code\":120001,\"msg\":\"unauthorized, please login again\",\"data\":null}");
        assertEquals(origin, findRoute(origin));
    }

    @Test public void olderSuccessfulConfigDoesNotRequireMessageField() throws Exception {
        respond("/music/api/v1/sys/config", 200, "{\"code\":0,\"data\":{\"serverVersion\":\"0.9.24\"}}");
        assertEquals(origin, findRoute(origin));
    }

    @Test public void relayRedirectAndHttpFailureRetainDistinctReasons() throws Exception {
        routes.put("/relay/music/api/v1/sys/config", request -> new MockResponse()
                .setResponseCode(302).addHeader("Location", origin + "/connect-page"));
        respond("/connect-page", 200, "<html>Detect FN ID</html>");
        respond("/public/music/api/v1/sys/config", 502, "bad gateway");
        try {
            findRoute(origin + "/relay", origin + "/public");
            fail("unreachable routes must not be selected");
        } catch (ApiException error) {
            assertTrue(error.getMessage().contains("跳转到网页"));
            assertTrue(error.getMessage().contains("HTTP 502"));
            assertFalse(error.getMessage().contains(origin));
            assertFalse(error.getMessage().contains("FN Connect 已开启"));
        }
    }

    @Test public void loginHttpUnauthorizedDoesNotClearExistingSession() throws Exception {
        api.session().saveLogin("http://existing.example", "old-token", "saved-user", "saved-address");
        respond("/music/api/v1/user/password-login", 401,
                "{\"code\":120001,\"msg\":\"unauthorized, please login again\",\"data\":null}");
        try {
            api.login(origin, "test-user", "test-password");
            fail("login should be rejected");
        } catch (ApiException error) {
            assertEquals(401, error.getStatusCode());
            assertTrue(error.getMessage().contains("鉴权被拒绝"));
            assertTrue(error.getMessage().contains("120001"));
            assertFalse(error.getMessage().contains("已失效"));
            assertEquals("old-token", api.session().getToken());
        }
    }

    @Test public void loginBusinessUnauthorizedExplainsAccountEntry() throws Exception {
        respond("/music/api/v1/user/password-login", 200,
                "{\"code\":120001,\"msg\":\"unauthorized, please login again\",\"data\":null}");
        try {
            api.login(origin, "test-user", "test-password");
            fail("login should be rejected");
        } catch (ApiException error) {
            assertTrue(error.getMessage().contains("音乐账号密码"));
            assertTrue(error.getMessage().contains("网页授权"));
            assertTrue(error.getMessage().contains("120001"));
            assertFalse(api.session().hasSession());
        }
    }

    @Test public void passwordSpacesAreHashedExactlyAndSuccessfulLoginPersistsToken() throws Exception {
        AtomicReference<String> postedBody = new AtomicReference<String>();
        routes.put("/music/api/v1/user/password-login", request -> {
            postedBody.set(request.getBody().readUtf8());
            return response(200, "{\"code\":0,\"msg\":\"\",\"data\":{\"userToken\":\"test-token\"}}");
        });
        api.login(origin, "music-user", " test password ");
        JSONObject request = new JSONObject(postedBody.get());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(" test password ".getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder();
        for (byte item : digest) expected.append(String.format("%02x", item & 255));
        assertEquals(expected.toString(), request.getString("password"));
        assertEquals("test-token", api.session().getToken());
        assertEquals(origin, api.session().getBaseUrl());
        assertEquals(origin, api.session().getLoginAddress());
    }

    @Test public void passwordLoginIsNotFollowedThroughRedirect() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        routes.put("/music/api/v1/user/password-login", request -> new MockResponse()
                .setResponseCode(303).addHeader("Location", origin + "/other-login"));
        routes.put("/other-login", request -> {
            targetRequests.incrementAndGet();
            return response(200, "<html>other login</html>");
        });
        try {
            api.login(origin, "test-user", "test-password");
            fail("redirect must be reported");
        } catch (ApiException error) {
            assertTrue(error.getMessage().contains("跳转到其他页面"));
            assertEquals(0, targetRequests.get());
        }
    }

    @Test public void gatewayHandshakeRetainsRoutingCookieForLoginAndLibrary() throws Exception {
        AtomicInteger gatewayRequests = new AtomicInteger();
        routes.put("/music/", request -> {
            gatewayRequests.incrementAndGet();
            assertEquals("https://fnos.net/", request.getHeader("Referer"));
            assertFalse(String.valueOf(request.getHeader("Cookie")).contains("music-token"));
            assertEquals("GET", request.getMethod());
            assertEquals(0, request.getBodySize());
            if (String.valueOf(request.getHeader("Cookie")).contains("mode=relay")) {
                return response(200, "<html>music login</html>");
            }
            return new MockResponse().setResponseCode(302).addHeader("Location", "/music/")
                    .addHeader("Set-Cookie", "mode=relay; Path=/; HttpOnly");
        });
        relaySession().bootstrap(new URL(origin + "/music/"), "https://fnos.net/");
        routes.put("/music/api/v1/user/password-login", request -> {
            assertTrue(request.getHeader("Cookie").contains("mode=relay"));
            assertFalse(request.getHeader("Cookie").contains("music-token"));
            return response(200, "{\"code\":0,\"data\":{\"userToken\":\"test-token\"}}");
        });
        routes.put("/music/api/v1/track/list", request -> {
            assertTrue(request.getHeader("Cookie").contains("mode=relay"));
            assertTrue(request.getHeader("Cookie").contains("music-token=test-token"));
            return response(200, "{\"code\":0,\"data\":{\"total\":1,\"list\":[{\"guid\":\"track-1\"}]}}");
        });
        api.login(origin, "test-user", "test-password");
        assertEquals(1, api.getTracks(1, 1, "").total);
        assertEquals(2, gatewayRequests.get());
        assertEquals("", relaySession().cookieHeader(new URL("http://localhost:1/music/")));
    }

    @Test public void gatewayDoesNotFollowCrossOriginRedirectOrKeepPartialCookies() throws Exception {
        routes.put("/music/", request -> new MockResponse().setResponseCode(302)
                .addHeader("Set-Cookie", "mode=relay; Path=/")
                .addHeader("Location", "https://other.example/music/"));
        try {
            relaySession().bootstrap(new URL(origin + "/music/"), "https://fnos.net/");
            fail("gateway redirect must stay on its origin");
        } catch (java.io.IOException error) {
            assertTrue(error.getMessage().contains("其他站点"));
            assertEquals("", relaySession().cookieHeader(new URL(origin + "/music/")));
        }
    }

    @Test public void relayDetectionRequiresHttpsAndOfficialDomainBoundary() throws Exception {
        assertEquals("https://fnos.net/", FnRelaySession.directoryReferer(new URL("https://myfnid.fnos.net:443/music/")));
        assertEquals("https://5ddd.com/", FnRelaySession.directoryReferer(new URL("https://myfnid.5ddd.com/music/")));
        assertEquals("", FnRelaySession.directoryReferer(new URL("http://myfnid.fnos.net/music/")));
        assertEquals("", FnRelaySession.directoryReferer(new URL("https://myfnid.fnos.net.other.example/music/")));
        assertEquals("", FnRelaySession.directoryReferer(new URL("https://192.168.1.1/music/")));
    }

    @Test public void apiForbiddenDoesNotInvalidateValidSession() throws Exception {
        api.session().saveLogin(origin, "test-token", "test-user", origin);
        respond("/music/api/v1/track/list", 403, "{\"code\":100003,\"msg\":\"forbidden\",\"data\":null}");
        try {
            api.getTracks(1, 20, "");
            fail("forbidden request should fail");
        } catch (ApiException error) {
            assertEquals(403, error.getStatusCode());
            assertEquals("test-token", api.session().getToken());
        }
    }

    @Test public void apiUnauthorizedStillInvalidatesExpiredSession() throws Exception {
        api.session().saveLogin(origin, "test-token", "test-user", origin);
        respond("/music/api/v1/track/list", 401, "{\"code\":99999,\"msg\":\"INVALID TOKEN\",\"data\":null}");
        try {
            api.getTracks(1, 20, "");
            fail("expired session should fail");
        } catch (ApiException error) {
            assertEquals(401, error.getStatusCode());
            assertFalse(api.session().hasSession());
        }
    }

    @Test public void networkErrorsHaveUsefulReasonsWithoutAddresses() {
        assertTrue(LoginDiagnostics.networkFailure(new SSLHandshakeException("sensitive-url")).contains("TLS"));
        assertEquals("域名解析失败", LoginDiagnostics.networkFailure(new UnknownHostException("sensitive-host")));
        assertEquals("连接超时", LoginDiagnostics.networkFailure(new SocketTimeoutException("sensitive-url")));
        assertEquals("网络连接失败", LoginDiagnostics.networkFailure(new Exception("sensitive-url")));
    }

    @Test public void retriesTransientDirectoryErrorButStopsOnUnknownFnId() throws Exception {
        respond("/one/api/v1/fn/con", 200, "{\"code\":3000009,\"msg\":\"Network Error\"}");
        respond("/two/api/v1/fn/con", 200, "{\"code\":0,\"data\":{\"ipv4\":[\"192.168.1.2\"]}}");
        String[] directories = {origin + "/one", origin + "/two"};
        assertTrue(FnConnectResolver.lookup("myfnid", directories).has("ipv4"));
        respond("/one/api/v1/fn/con", 200, "{\"code\":3000006}");
        try { FnConnectResolver.lookup("myfnid", directories); fail(); }
        catch (ApiException expected) { assertTrue(expected.getMessage().contains("不存在")); }
        assertEquals(3, server.getRequestCount());
    }

    @Test public void loginDiagnosticsUsePeerNotClientIpAndExplainWrongClock() throws Exception {
        String raw = "failed to connect to example.test/192.168.1.20 (port 5666) from /192.168.1.9 after 8000ms";
        String hint = LoginDiagnostics.connectionHint(new java.net.ConnectException(raw), "http://example.test:5666");
        assertTrue(hint.contains("内网")); assertTrue(hint.contains("超时")); assertFalse(hint.contains("192.168"));
        hint = LoginDiagnostics.connectionHint(new java.net.ConnectException(raw.replace("example.test/192.168.1.20", "example.test/8.8.8.8")), "http://example.test:5666");
        assertFalse(hint.contains("内网")); assertTrue(hint.contains("端口"));
        SSLHandshakeException handshake = new SSLHandshakeException("handshake failed");
        handshake.initCause(new java.security.cert.CertificateNotYetValidException());
        assertTrue(LoginDiagnostics.networkFailure(handshake).contains("校准"));
    }

    @Test public void bareDomainSelectsWorkingAlternativeBeforeSendingCredentials() throws Exception {
        respond("/wrong/music/api/v1/sys/config", 502, "bad gateway");
        respond("/working/music/api/v1/sys/config", 200, "{\"code\":0,\"data\":{}}");
        String selected = api.selectBareDomainRoute(origin + "/wrong", origin + "/working");
        respond("/working/music/api/v1/user/password-login", 200, "{\"code\":0,\"data\":{\"userToken\":\"test\"}}");
        api.login(selected, "alice", "test");
        assertEquals("GET", server.takeRequest().getMethod());
        assertEquals("GET", server.takeRequest().getMethod());
        RecordedRequest login = server.takeRequest();
        assertEquals("POST", login.getMethod()); assertTrue(login.getPath().startsWith("/working/"));
        assertEquals(3, server.getRequestCount());
    }

    @Test public void pastedMusicPageAndRelayAndTlsPortsNormalizeCorrectly() throws Exception {
        assertEquals("https://example.test/reverse", FnApiClient.normalizeBaseUrl("https://example.test/reverse/music/#/library"));
        assertEquals("https://myfnid.fnos.net", FnApiClient.normalizeBaseUrl("myfnid.fnos.net"));
        assertEquals("https://example.test:5667", FnApiClient.normalizeBaseUrl("example.test:5667"));
        assertEquals("http://192.168.1.20:5666", FnApiClient.normalizeBaseUrl("192.168.1.20"));
    }

    @Test public void lyricsRemainReadableOfflineAndAreIsolatedByUser() throws Exception {
        api.session().saveLogin(origin, "token", "user1", origin);
        respond("/music/api/v1/lyric/list", 200, "{\"code\":0,\"data\":{\"list\":[{\"content\":\"[00:01]hello\"}]}}");
        assertEquals("[00:01]hello", api.getLyric("one-song"));
        respond("/music/api/v1/lyric/list", 503, "unavailable");
        assertEquals("[00:01]hello", api.snapshot().getLyric("one-song"));
        assertEquals(1, server.getRequestCount());
        api.session().saveLogin(origin, "other-token", "user2", origin);
        try { api.getLyric("one-song"); fail(); }
        catch (ApiException expected) { assertEquals(503, expected.getStatusCode()); }
    }

    private String findRoute(String... routes) throws Exception {
        Method method = FnApiClient.class.getDeclaredMethod("findReachableFnIdRoute", List.class);
        method.setAccessible(true);
        try { return (String) method.invoke(api, Arrays.asList(routes)); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }

    private FnRelaySession relaySession() throws Exception {
        java.lang.reflect.Field field = FnApiClient.class.getDeclaredField("relaySession");
        field.setAccessible(true);
        return (FnRelaySession) field.get(api);
    }

    private void respond(String path, int status, String body) {
        routes.put(path, request -> response(status, body));
    }

    private static MockResponse response(int status, String body) {
        return new MockResponse().setResponseCode(status).setBody(body)
                .addHeader("Content-Type", body.startsWith("{") ? "application/json" : "text/html");
    }

    private interface RequestHandler {
        MockResponse handle(RecordedRequest request);
    }
}
