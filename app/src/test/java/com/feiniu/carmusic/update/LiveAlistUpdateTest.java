package com.feiniu.carmusic.update;

import android.content.Context;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

import static org.junit.Assert.*;

/** Explicitly enabled with -PliveUpdateTest=true; downloads and verifies, never installs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LiveAlistUpdateTest {
    @Test public void publicManifestAndApkWorkThroughActualUpdater() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("feiniu.liveUpdates"));
        String manifestUrl = "http://ikuai.qiuxiaoyu.top:5244/d/20T%E5%A4%A7%E7%A1%AC%E7%9B%98/QY_net/feiniu-updates/update.json";
        UpdateInfo info = (UpdateInfo) invoke("fetchManifest", new Class<?>[] {String.class}, new Object[] {manifestUrl});
        assertTrue(info.versionCode >= 14);
        assertTrue(new URI(info.apkUrl).getPath().startsWith("/d/20T大硬盘/QY_net/feiniu-updates/"));
        Context context = RuntimeEnvironment.getApplication();
        File apk = (File) invoke("downloadAndVerify", new Class<?>[] {Context.class, UpdateInfo.class}, new Object[] {context, info});
        assertTrue(apk.isFile());
        assertTrue(apk.length() > 0);
        assertEquals(info.sha256, invoke("sha256", new Class<?>[] {File.class}, new Object[] {apk}));
    }

    private Object invoke(String name, Class<?>[] types, Object[] args) throws Exception {
        Method method = AppUpdater.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException error) { throw (Exception) error.getCause(); }
    }
}
