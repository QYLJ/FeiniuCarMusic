package com.feiniu.carmusic.feedback;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;

import com.feiniu.carmusic.diagnostics.AppLog;

import org.json.JSONObject;

import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/** Builds a bounded, human-readable evidence document before public-key encryption. */
public final class FeedbackEvidence {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_MESSAGE = 6000;
    private static final int MAX_CONTACT = 240;
    private static final int MAX_LOG_BYTES = 384 * 1024;

    private FeedbackEvidence() { }

    public static Report create(Context context, String category, String message,
                                String contact, boolean includeLogs) throws Exception {
        String id = timestampForId() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("reportId", id);
        root.put("createdAtUtc", utcNow());
        root.put("category", bounded(category, 80));
        root.put("message", bounded(message, MAX_MESSAGE));
        root.put("contact", bounded(contact, MAX_CONTACT));

        JSONObject consent = new JSONObject();
        consent.put("includeRuntimeLog", includeLogs);
        consent.put("notice", "No password, login token, cookie or Android ID is intentionally collected.");
        root.put("consent", consent);

        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        JSONObject app = new JSONObject();
        app.put("package", context.getPackageName());
        app.put("versionName", packageInfo.versionName == null ? "" : packageInfo.versionName);
        app.put("versionCode", packageInfo.versionCode);
        app.put("processUptimeMs", SystemClock.elapsedRealtime());
        root.put("app", app);

        JSONObject device = new JSONObject();
        device.put("manufacturer", safe(Build.MANUFACTURER));
        device.put("brand", safe(Build.BRAND));
        device.put("model", safe(Build.MODEL));
        device.put("device", safe(Build.DEVICE));
        device.put("androidRelease", safe(Build.VERSION.RELEASE));
        device.put("apiLevel", Build.VERSION.SDK_INT);
        device.put("cpuAbi", safe(Build.CPU_ABI));
        device.put("locale", Locale.getDefault().toString());
        device.put("timezone", TimeZone.getDefault().getID());
        root.put("device", device);

        JSONObject runtime = new JSONObject();
        Runtime javaRuntime = Runtime.getRuntime();
        runtime.put("memoryMaxBytes", javaRuntime.maxMemory());
        runtime.put("memoryAllocatedBytes", javaRuntime.totalMemory());
        runtime.put("memoryFreeBytes", javaRuntime.freeMemory());
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            runtime.put("systemAvailableMemoryBytes", memory.availMem);
            runtime.put("systemLowMemory", memory.lowMemory);
        }
        runtime.put("appStorageAvailableBytes", availableBytes(context.getFilesDir().getAbsolutePath()));
        root.put("runtime", runtime);

        JSONObject network = new JSONObject();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo active = connectivity == null ? null : connectivity.getActiveNetworkInfo();
        network.put("connected", active != null && active.isConnected());
        network.put("type", active == null ? "none" : safe(active.getTypeName()));
        network.put("subtype", active == null ? "" : safe(active.getSubtypeName()));
        root.put("network", network);

        JSONObject evidence = new JSONObject();
        evidence.put("runtimeLogBytes", AppLog.size());
        if (includeLogs) {
            evidence.put("runtimeLog", AppLog.recent(MAX_LOG_BYTES));
            evidence.put("lastCrash", AppLog.lastCrash());
        }
        root.put("evidence", evidence);
        return new Report(id, root.toString(2).getBytes(UTF8));
    }

    private static long availableBytes(String path) {
        try {
            StatFs stat = new StatFs(path);
            return (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static String bounded(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "<truncated>";
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String timestampForId() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    public static final class Report {
        public final String id;
        public final byte[] json;
        Report(String id, byte[] json) { this.id = id; this.json = json; }
    }
}
