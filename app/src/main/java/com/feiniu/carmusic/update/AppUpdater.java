package com.feiniu.carmusic.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.TlsCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdater {
    private static final long AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int DOWNLOAD_ATTEMPTS = 3;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean checking;
    private static boolean downloading;
    private static WeakReference<Activity> foregroundActivity = new WeakReference<Activity>(null);
    private static AlertDialog availableDialog;
    private static int promptedVersionCode;

    private AppUpdater() { }

    public static void onResume(Activity activity) {
        onResume(activity, activity.getString(R.string.update_manifest_url).trim());
    }

    static void onResume(Activity activity, String manifestUrl) {
        foregroundActivity = new WeakReference<Activity>(activity);
        check(activity, false, manifestUrl);
    }

    public static void onPause(Activity activity) {
        if (foregroundActivity.get() != activity) return;
        foregroundActivity.clear();
        if (availableDialog != null) {
            // A navigation/background transition must not count as choosing "later".
            availableDialog.dismiss();
            promptedVersionCode = 0;
        }
    }

    public static void check(Activity activity, boolean userInitiated) {
        check(activity, userInitiated, activity.getString(R.string.update_manifest_url).trim());
    }

    static void check(final Activity activity, final boolean userInitiated, final String manifestUrl) {
        if (manifestUrl.length() == 0) {
            if (userInitiated) {
                new AlertDialog.Builder(activity)
                        .setTitle("尚未配置更新地址")
                        .setMessage("此安装包未设置更新服务，请联系开发者获取新版安装包。")
                        .setPositiveButton("知道了", null)
                        .show();
            }
            return;
        }
        if (!isHttpUrl(manifestUrl)) {
            if (userInitiated) showError(activity, "更新地址必须使用 http:// 或 https://");
            return;
        }
        final Context context = activity.getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("app_updater", Context.MODE_PRIVATE);
        UpdateInfo cached = cachedManifest(context, manifestUrl);
        long now = System.currentTimeMillis();
        long elapsed = now - prefs.getLong("last_check", 0L);
        if (!userInitiated && cached != null && elapsed >= 0L && elapsed < AUTO_CHECK_INTERVAL_MS) {
            showResult(activity, cached, false);
            return;
        }
        synchronized (AppUpdater.class) {
            if (downloading) {
                if (userInitiated) Toast.makeText(activity, "正在下载更新…", Toast.LENGTH_SHORT).show();
                return;
            }
            if (checking) {
                if (userInitiated) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
                return;
            }
            checking = true;
        }
        if (userInitiated) Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                try {
                    final UpdateInfo info = fetchManifest(manifestUrl);
                    saveManifest(context, manifestUrl, info);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            finishChecking();
                            Activity target = foregroundActivity.get();
                            if (target != null && !target.isFinishing()) showResult(target, info, userInitiated);
                        }
                    });
                } catch (final Exception error) {
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            finishChecking();
                            Activity target = foregroundActivity.get();
                            if (target == null || target.isFinishing()) return;
                            if (userInitiated) {
                                showError(target, "检查更新失败：" + safeMessage(error));
                            } else {
                                UpdateInfo saved = cachedManifest(context, manifestUrl);
                                if (saved != null) showResult(target, saved, false);
                            }
                        }
                    });
                }
            }
        });
    }

    private static void showResult(Activity activity, UpdateInfo info, boolean userInitiated) {
        if (activity.isFinishing() || foregroundActivity.get() != activity || downloading) return;
        try {
            if (info.versionCode <= currentVersionCode(activity)) {
                if (userInitiated) Toast.makeText(activity, "当前已是最新版本", Toast.LENGTH_SHORT).show();
            } else if (info.minSdk > Build.VERSION.SDK_INT) {
                if (userInitiated) showError(activity, "新版本要求 Android API " + info.minSdk + " 及以上");
            } else if ((availableDialog == null || !availableDialog.isShowing())
                    && (userInitiated || info.versionCode > promptedVersionCode)) {
                showAvailable(activity, info);
            }
        } catch (Exception error) {
            if (userInitiated) showError(activity, "无法读取当前版本：" + safeMessage(error));
        }
    }

    private static void saveManifest(Context context, String manifestUrl, UpdateInfo info) throws Exception {
        JSONObject json = new JSONObject();
        json.put("versionCode", info.versionCode).put("versionName", info.versionName)
                .put("apkUrl", info.apkUrl).put("sha256", info.sha256)
                .put("changelog", info.changelog).put("minSdk", info.minSdk);
        context.getSharedPreferences("app_updater", Context.MODE_PRIVATE).edit()
                .putString("manifest_source", manifestUrl).putString("manifest", json.toString())
                .putLong("last_check", System.currentTimeMillis()).apply();
    }

    private static UpdateInfo cachedManifest(Context context, String manifestUrl) {
        SharedPreferences prefs = context.getSharedPreferences("app_updater", Context.MODE_PRIVATE);
        if (!manifestUrl.equals(prefs.getString("manifest_source", ""))) return null;
        try {
            return parseManifest(manifestUrl, prefs.getString("manifest", ""));
        } catch (Exception ignored) {
            prefs.edit().remove("manifest").remove("manifest_source").remove("last_check").apply();
            return null;
        }
    }

    public static void resumePendingInstall(Activity activity, int requestCode) {
        if (requestCode != UpdateInstallApi26.REQUEST_UNKNOWN_SOURCES) return;
        File apk = downloadedApk(activity);
        if (apk.isFile()) install(activity, apk);
    }

    static File downloadedApk(Context context) {
        return new File(new File(context.getCacheDir(), "updates"),
                UpdateFileProvider.FILE_NAME);
    }

    private static void showAvailable(final Activity activity, final UpdateInfo info) {
        String message = "当前版本：" + versionName(activity) + "\n"
                + "新版本：" + info.versionName + "\n\n"
                + "更新内容：\n" + (info.changelog.length() == 0 ? "开发者未提供更新说明。" : info.changelog);
        promptedVersionCode = info.versionCode;
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(message)
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载并安装", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        download(activity, info);
                    }
                }).create();
        dialog.setOwnerActivity(activity);
        availableDialog = dialog;
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dismissed) {
                if (availableDialog == dialog) availableDialog = null;
            }
        });
        dialog.show();
    }

    private static void download(final Activity activity, final UpdateInfo info) {
        synchronized (AppUpdater.class) {
            if (downloading) return;
            downloading = true;
        }
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("正在下载更新")
                .setMessage("下载完成后将打开系统安装界面。")
                .setCancelable(false)
                .create();
        progress.show();
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                try {
                    final File apk = downloadAndVerify(activity, info);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            finishDownloading();
                            progress.dismiss();
                            if (!activity.isFinishing()) install(activity, apk);
                        }
                    });
                } catch (final Exception error) {
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            finishDownloading();
                            progress.dismiss();
                            if (!activity.isFinishing()) {
                                showError(activity, "下载更新失败：" + safeMessage(error));
                            }
                        }
                    });
                }
            }
        });
    }

    private static UpdateInfo fetchManifest(String manifestUrl) throws Exception {
        byte[] body = readUrl(manifestUrl, MAX_MANIFEST_BYTES);
        return parseManifest(manifestUrl, new String(body, "UTF-8"));
    }

    private static UpdateInfo parseManifest(String manifestUrl, String body) throws Exception {
        String text = body.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (Exception error) {
            throw new IllegalArgumentException("更新服务器未返回有效版本信息，可能返回了网盘网页或登录页面");
        }
        if (!json.has("versionCode") || !json.has("versionName") || !json.has("apkUrl") || !json.has("sha256")) {
            throw new IllegalArgumentException("版本信息不完整，请联系开发者检查更新清单");
        }
        int code = json.getInt("versionCode");
        String name = json.getString("versionName").trim();
        String rawApkUrl = json.getString("apkUrl").trim();
        String apkUrl = new URL(new URL(manifestUrl), rawApkUrl).toString();
        String sha256 = json.getString("sha256").replace(" ", "").toUpperCase(Locale.US);
        String changelog = json.optString("changelog", "").trim();
        int minSdk = json.optInt("minSdk", 14);
        if (code <= 0 || name.length() == 0 || !isHttpUrl(apkUrl)
                || !sha256.matches("[0-9A-F]{64}") || minSdk < 1) {
            throw new IllegalArgumentException("更新清单格式无效");
        }
        return new UpdateInfo(code, name, apkUrl, sha256, changelog, minSdk);
    }

    private static File downloadAndVerify(Context context, UpdateInfo info) throws Exception {
        File target = downloadedApk(context);
        File directory = target.getParentFile();
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建更新缓存目录");
        }
        if (Build.VERSION.SDK_INT < 24
                && (!directory.setReadable(true, false)
                || !directory.setExecutable(true, false))) {
            throw new IllegalStateException("无法授权系统安装器读取更新目录");
        }
        File partial = new File(directory, target.getName() + ".part");
        Exception lastError = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            if (partial.exists()) partial.delete();
            try {
                downloadFile(info.apkUrl, partial);
                lastError = null;
                break;
            } catch (Exception error) {
                lastError = error;
                partial.delete();
            }
        }
        if (lastError != null) throw lastError;
        String actual = sha256(partial);
        if (!info.sha256.equals(actual)) {
            partial.delete();
            throw new SecurityException("APK SHA-256 校验失败");
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧更新包");
        if (!partial.renameTo(target)) throw new IllegalStateException("无法保存更新包");
        if (Build.VERSION.SDK_INT < 24 && !target.setReadable(true, false)) {
            target.delete();
            throw new IllegalStateException("无法授权系统安装器读取更新包");
        }
        return target;
    }

    private static void downloadFile(String apkUrl, File partial) throws Exception {
        HttpURLConnection connection = open(apkUrl, false);
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = connection.getInputStream();
            output = new FileOutputStream(partial);
            String contentType = connection.getContentType();
            if (contentType != null && contentType.toLowerCase(Locale.US).contains("text/html")) {
                throw new IllegalArgumentException("更新下载地址返回了网页，无法获取安装包");
            }
            long expected = contentLength(connection);
            long downloaded = 0L;
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                    downloaded += read;
                }
            }
            if (expected >= 0L && downloaded != expected) throw new java.io.IOException("安装包下载不完整，请重试");
            output.getFD().sync();
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            connection.disconnect();
        }
    }

    private static byte[] readUrl(String url, int limit) throws Exception {
        HttpURLConnection connection = open(url, true);
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            input = connection.getInputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > limit) throw new IllegalArgumentException("更新清单过大");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, boolean manifest) throws Exception {
        URL current = new URL(AlistDownloadResolver.resolve(url));
        for (int hop = 0; hop <= 5; hop++) {
            if (!isHttpUrl(current.toExternalForm())) throw new IllegalArgumentException("更新地址必须使用 HTTP 或 HTTPS");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            try {
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(manifest ? 20000 : 60000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "FeiniuCarMusic-Updater Android");
                if (manifest) {
                    connection.setRequestProperty("Cache-Control", "no-cache, no-store");
                    connection.setRequestProperty("Pragma", "no-cache");
                }
                TlsCompat.configure(connection);
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().length() == 0) throw new IllegalStateException("更新下载重定向缺少地址");
                    URL next = new URL(current, location);
                    if ("https".equalsIgnoreCase(current.getProtocol()) && "http".equalsIgnoreCase(next.getProtocol())) {
                        throw new IllegalStateException("更新服务不能从 HTTPS 降级到 HTTP");
                    }
                    current = next;
                    connection.disconnect();
                    continue;
                }
                if (code == 401 || code == 403) {
                    throw new IllegalStateException("更新服务器拒绝访问，请检查公开下载权限（HTTP " + code + "）");
                }
                if (code < 200 || code >= 300) throw new IllegalStateException("更新服务器返回 HTTP " + code);
                return connection;
            } catch (Exception error) {
                connection.disconnect();
                throw error;
            }
        }
        throw new IllegalStateException("更新下载重定向次数过多");
    }

    private static long contentLength(HttpURLConnection connection) {
        try { return Long.parseLong(connection.getHeaderField("Content-Length")); }
        catch (Exception ignored) { return -1L; }
    }

    private static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !UpdateInstallApi26.canInstall(activity)) {
            Toast.makeText(activity, "请允许此应用安装更新包", Toast.LENGTH_LONG).show();
            UpdateInstallApi26.requestPermission(activity);
            return;
        }
        Uri uri = Build.VERSION.SDK_INT >= 24
                ? Uri.parse("content://" + UpdateFileProvider.AUTHORITY + "/"
                        + UpdateFileProvider.FILE_NAME)
                : Uri.fromFile(apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive");
        if (Build.VERSION.SDK_INT >= 24) {
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        activity.startActivity(install);
    }

    private static int currentVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return info.versionCode;
    }

    private static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        } finally {
            input.close();
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02X", value));
        return result.toString();
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static void showError(Activity activity, String message) {
        new AlertDialog.Builder(activity).setTitle("在线更新")
                .setMessage(message).setPositiveButton("确定", null).show();
    }

    private static synchronized void finishChecking() {
        checking = false;
    }

    private static synchronized void finishDownloading() {
        downloading = false;
    }
}
