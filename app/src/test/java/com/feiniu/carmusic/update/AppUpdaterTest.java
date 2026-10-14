package com.feiniu.carmusic.update;

import android.content.Context;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AppUpdaterTest {
    private MockWebServer server;
    private Context context;
    private static final byte[] APK_BYTES = "mock APK payload for integrity regression".getBytes(StandardCharsets.UTF_8);

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        context = RuntimeEnvironment.getApplication();
    }

    @After public void tearDown() throws Exception { server.shutdown(); }

    @Test public void alistRelativeApkAndManifestCacheHeadersWork() throws Exception {
        server.enqueue(new MockResponse().setBody(manifest("FeiniuCarMusic-14.apk", hash(APK_BYTES))));
        String url = server.url("/d/feiniu-updates/update.json?sign=public%3A0").toString();
        UpdateInfo info = fetch(url);
        assertEquals(server.url("/d/feiniu-updates/FeiniuCarMusic-14.apk").toString(), info.apkUrl);
        assertEquals(14, info.versionCode);
        assertEquals(14, info.minSdk);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("no-cache, no-store", request.getHeader("Cache-Control"));
        assertEquals("no-cache", request.getHeader("Pragma"));
        assertTrue(request.getPath().contains("sign=public%3A0"));
        assertNull(request.getHeader("Cookie"));
        assertNull(request.getHeader("Authorization"));
    }

    @Test public void utf8BomManifestIsAccepted() throws Exception {
        server.enqueue(new MockResponse().setBody("\uFEFF" + manifest("app.apk", hash(APK_BYTES))));
        assertEquals("0.3.7-alist-update", fetch(server.url("/update.json").toString()).versionName);
    }

    @Test public void signedAlistManifestAndApkAreResolvedIndependently() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"data\":{\"sign\":\"manifest-sign:0\"}}"));
        server.enqueue(new MockResponse().setBody(manifest("FeiniuCarMusic-14.apk", hash(APK_BYTES))));
        server.enqueue(new MockResponse().setBody("{\"code\":200,\"data\":{\"sign\":\"apk-sign:0\"}}"));
        server.enqueue(new MockResponse().setBody(new Buffer().write(APK_BYTES)));
        UpdateInfo info = fetch(server.url("/d/feiniu-updates/update.json").toString());
        assertEquals(hash(APK_BYTES), hash(read(download(info))));
        RecordedRequest manifestLookup = server.takeRequest();
        assertEquals("/update.json", new JSONObject(manifestLookup.getBody().readUtf8()).getString("path"));
        assertTrue(server.takeRequest().getPath().contains("sign=manifest-sign%3A0"));
        RecordedRequest apkLookup = server.takeRequest();
        assertEquals("/FeiniuCarMusic-14.apk", new JSONObject(apkLookup.getBody().readUtf8()).getString("path"));
        assertTrue(server.takeRequest().getPath().contains("sign=apk-sign%3A0"));
    }

    @Test public void alistWebpageGetsClearErrorInsteadOfJsonParserText() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<!doctype html><title>AList</title>"));
        try { fetch(server.url("/feiniu-updates/update.json").toString()); fail("webpage is not a manifest"); }
        catch (IllegalArgumentException error) { assertTrue(error.getMessage().contains("网盘网页或登录页面")); }
    }

    @Test public void protectedUpdateDirectoryReportsAccessFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("forbidden"));
        try { fetch(server.url("/update.json").toString()); fail("private directory should fail"); }
        catch (IllegalStateException error) {
            assertTrue(error.getMessage().contains("公开下载权限"));
            assertTrue(error.getMessage().contains("403"));
        }
    }

    @Test public void malformedHashAndUnsupportedApkSchemeAreRejected() throws Exception {
        server.enqueue(new MockResponse().setBody(manifest("app.apk", "not-a-sha256")));
        server.enqueue(new MockResponse().setBody(manifest("file:///untrusted.apk", hash(APK_BYTES))));
        for (int i = 0; i < 2; i++) {
            try { fetch(server.url("/update.json").toString()); fail("invalid manifest should fail"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("清单格式无效")); }
        }
    }

    @Test public void manifestSizeIsBounded() throws Exception {
        char[] large = new char[256 * 1024 + 1];
        Arrays.fill(large, 'x');
        server.enqueue(new MockResponse().setBody(new String(large)));
        try { fetch(server.url("/update.json").toString()); fail("oversized manifest should fail"); }
        catch (IllegalArgumentException error) { assertTrue(error.getMessage().contains("过大")); }
    }

    @Test public void alistDownloadRedirectProducesVerifiedApk() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/real/apk-file"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/octet-stream")
                .setBody(new Buffer().write(APK_BYTES)));
        File target = download(info(hash(APK_BYTES)));
        assertEquals(hash(APK_BYTES), hash(read(target)));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void incompleteDownloadIsRetriedBeforeHashVerification() throws Exception {
        server.enqueue(new MockResponse().setBody(new Buffer().write(APK_BYTES))
                .setHeader("Content-Length", APK_BYTES.length + 5).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
        server.enqueue(new MockResponse().setBody(new Buffer().write(APK_BYTES)));
        assertEquals(hash(APK_BYTES), hash(read(download(info(hash(APK_BYTES))))));
        assertEquals(2, server.getRequestCount());
    }

    @Test public void hashMismatchKeepsPreviousVerifiedFileAndDeletesPartial() throws Exception {
        File previous = AppUpdater.downloadedApk(context);
        assertTrue(previous.getParentFile().exists() || previous.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(previous)) { output.write("previous verified file".getBytes(StandardCharsets.UTF_8)); }
        String previousHash = hash(read(previous));
        server.enqueue(new MockResponse().setBody(new Buffer().write(APK_BYTES)));
        try { download(info(hash("different bytes".getBytes(StandardCharsets.UTF_8)))); fail("wrong hash should fail"); }
        catch (SecurityException error) { assertTrue(error.getMessage().contains("SHA-256")); }
        assertEquals(previousHash, hash(read(previous)));
        assertFalse(new File(previous.getParentFile(), previous.getName() + ".part").exists());
    }

    @Test public void htmlDownloadIsNeverAcceptedAsApk() throws Exception {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("<html>AList</html>"));
        try { download(info(hash("<html>AList</html>".getBytes(StandardCharsets.UTF_8)))); fail("HTML must be rejected"); }
        catch (IllegalArgumentException error) { assertTrue(error.getMessage().contains("返回了网页")); }
        assertEquals(3, server.getRequestCount());
    }

    @Test public void endlessRedirectsAreBounded() throws Exception {
        for (int i = 0; i < 6; i++) server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/loop"));
        try { fetch(server.url("/loop").toString()); fail("redirect loop should fail"); }
        catch (IllegalStateException error) { assertTrue(error.getMessage().contains("次数过多")); }
        assertEquals(6, server.getRequestCount());
    }

    private UpdateInfo info(String sha) {
        return new UpdateInfo(14, "0.3.7-alist-update", server.url("/downloads/app.apk").toString(), sha, "更新说明", 14);
    }

    private static String manifest(String apkUrl, String sha) throws Exception {
        return new JSONObject().put("versionCode", 14).put("versionName", "0.3.7-alist-update")
                .put("apkUrl", apkUrl).put("sha256", sha).put("minSdk", 14).put("changelog", "更新说明").toString();
    }

    private UpdateInfo fetch(String url) throws Exception {
        return (UpdateInfo) invoke("fetchManifest", new Class<?>[] {String.class}, new Object[] {url});
    }

    private File download(UpdateInfo info) throws Exception {
        return (File) invoke("downloadAndVerify", new Class<?>[] {Context.class, UpdateInfo.class}, new Object[] {context, info});
    }

    private Object invoke(String name, Class<?>[] types, Object[] args) throws Exception {
        Method method = AppUpdater.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.US, "%02X", value & 255));
        return hex.toString();
    }

    private static byte[] read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            int count;
            while (offset < bytes.length && (count = input.read(bytes, offset, bytes.length - offset)) != -1) offset += count;
            assertEquals(bytes.length, offset);
        }
        return bytes;
    }
}
