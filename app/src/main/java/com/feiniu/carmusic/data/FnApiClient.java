package com.feiniu.carmusic.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import com.feiniu.carmusic.diagnostics.AppLog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import com.feiniu.carmusic.model.Track;
import com.feiniu.carmusic.model.TrackPage;
import com.feiniu.carmusic.model.MediaCollection;
import com.feiniu.carmusic.model.MediaCollectionPage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;

public final class FnApiClient {
    private static final String TAG = "FnApiClient";
    private static final String API_PREFIX = "/music/api/v1";
    private static final int MAX_TRACK_DOWNLOAD_ATTEMPTS = 12;
    private static volatile FnApiClient instance;

    private final SessionStore session;
    private final Context context;
    private final FnRelaySession relaySession;
    private final Object routeLock = new Object();
    interface RouteLookup { List<String> resolve(String fnId) throws ApiException; }
    private RouteLookup routeLookup = new RouteLookup() {
        @Override public List<String> resolve(String fnId) throws ApiException {
            return FnConnectResolver.resolveCandidates(fnId);
        }
    };
    private String lastFailedRoute = "";
    private long lastRecoveryAt;

    public interface TrackBufferListener {
        void onBufferReady(long downloadedBytes, long totalBytes) throws Exception;
    }

    private FnApiClient(Context context) {
        this(context, new SessionStore(context), new FnRelaySession());
    }

    private FnApiClient(Context context, SessionStore session, FnRelaySession relay) {
        TlsCompat.initialize();
        this.context = context.getApplicationContext();
        this.session = session;
        relaySession = relay;
    }

    public FnApiClient snapshot() {
        FnApiClient client = new FnApiClient(context, session.snapshot(), relaySession);
        client.routeLookup = routeLookup;
        return client;
    }

    public static FnApiClient get(Context context) {
        if (instance == null) {
            synchronized (FnApiClient.class) {
                if (instance == null) instance = new FnApiClient(context.getApplicationContext());
            }
        }
        return instance;
    }

    public SessionStore session() {
        return session;
    }

    public void login(String serverInput, String username, String password) throws Exception {
        LoginResult result = authenticate(serverInput, username, password);
        session.saveLogin(result.baseUrl, result.token, result.username, result.loginAddress);
    }

    public LoginResult authenticate(String serverInput, String username, String password) throws Exception {
        String loginAddress = serverInput == null ? "" : serverInput.trim();
        String baseUrl;
        if (FnConnectResolver.looksLikeFnIdInput(loginAddress)) {
            String fnId = FnConnectResolver.normalizeFnId(loginAddress);
            baseUrl = findReachableFnIdRoute(routeLookup.resolve(fnId));
            loginAddress = fnId;
        } else {
            baseUrl = directLoginBase(loginAddress);
        }
        JSONObject request = new JSONObject();
        request.put("username", username);
        request.put("password", sha256(password == null ? "" : password));
        request.put("deviceId", session.getDeviceId());

        Response response = execute(baseUrl, "/user/password-login", "POST", request.toString(), false);
        JSONObject root;
        try { root = parseRoot(response.body); }
        catch (ApiException invalidResponse) {
            throw new ApiException("地址可访问，但登录接口未返回飞牛音乐数据。请检查音乐应用是否已安装，以及域名、端口和反向代理路径");
        }
        int code = root.optInt("code", 0);
        if (code != 0) {
            throw new ApiException(LoginDiagnostics.loginFailure(response.status, code,
                    root.optString("msg", root.optString("message", "登录失败"))));
        }
        JSONObject data = root.optJSONObject("data");
        String token = data == null ? "" : data.optString("userToken", "");
        if (token.length() == 0) throw new ApiException("登录失败：服务器未返回 Token");
        return new LoginResult(baseUrl, token, username, loginAddress);
    }

    public static final class LoginResult {
        public final String baseUrl, token, username, loginAddress;
        LoginResult(String base, String token, String user, String address) {
            baseUrl = base; this.token = token; username = user; loginAddress = address;
        }
    }

    private String findReachableFnIdRoute(List<String> candidates) throws ApiException {
        LinkedHashSet<String> failures = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            if (isMobileNetwork() && isLocalAddress(candidate)) continue;
            try {
                return probeMusicServer(candidate);
            } catch (ApiException error) {
                String route = isLocalAddress(candidate) ? "内网" : "外网";
                try { if (!FnRelaySession.directoryReferer(new URL(candidate)).isEmpty()) route = "FN 中继"; }
                catch (java.net.MalformedURLException ignored) { }
                failures.add(route + "：" + error.getMessage());
            }
        }
        StringBuilder detail = new StringBuilder();
        if (failures.isEmpty()) failures.add("目录只返回了内网地址，移动网络无法访问。请启用 FN Connect 中继或配置公网地址");
        for (String failure : failures) {
            if (detail.length() > 0) detail.append("；");
            detail.append(failure);
        }
        throw new ApiException("FN ID 已找到，但音乐线路均不可用：" + detail
                + "。请用同一网络的官方音乐页面检查访问");
    }

    private boolean isMobileNetwork() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        return network != null && network.isConnected() && network.getType() == ConnectivityManager.TYPE_MOBILE;
    }

    static boolean isLocalAddress(String address) {
        try {
            String host = new URL(address).getHost().toLowerCase(java.util.Locale.US);
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            boolean ipv4 = host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
            if (ipv4) for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) return false;
            if (ipv4 && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("169.254."))) return true;
            if (ipv4 && host.startsWith("172.")) {
                String[] parts = host.split("\\.");
                int second = parts.length == 4 ? Integer.parseInt(parts[1]) : 0;
                return second >= 16 && second <= 31;
            }
            return host.indexOf(':') >= 0 && (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:"));
        } catch (Exception ignored) { return false; }
    }

    private String currentMusicBase() throws ApiException {
        String base = session.getBaseUrl();
        if (isMobileNetwork() && isLocalAddress(base)) {
            if (recoverRoute(base)) return session.getBaseUrl();
            throw new ApiException("当前为移动网络，无法访问 NAS 内网地址。请使用 FN ID 或可用的外网地址登录");
        }
        return base;
    }

    /** Re-resolve only the saved FN ID; never guess another NAS or resend a saved password. */
    private boolean recoverRoute(String failedBase) throws ApiException {
        if (Thread.currentThread().isInterrupted()) return false;
        synchronized (routeLock) {
            if (!failedBase.equals(session.getBaseUrl())) return session.hasSession();
            String loginAddress = session.getLoginAddress();
            String token = session.getToken();
            if (token.length() == 0 || !FnConnectResolver.looksLikeFnIdInput(loginAddress)) return false;
            try { loginAddress = FnConnectResolver.normalizeFnId(loginAddress); }
            catch (ApiException invalidId) { return false; }
            long now = android.os.SystemClock.elapsedRealtime();
            if (failedBase.equals(lastFailedRoute) && now - lastRecoveryAt < 15000L) return false;
            lastFailedRoute = failedBase;
            lastRecoveryAt = now;
            List<String> candidates = routeLookup.resolve(loginAddress);
            for (String candidate : candidates) {
                if (Thread.currentThread().isInterrupted()) return false;
                if (candidate.equals(failedBase) || (isMobileNetwork() && isLocalAddress(candidate))) continue;
                try {
                    String reachable = probeMusicServer(candidate);
                    if (session.changeRoute(failedBase, token, reachable)) {
                        AppLog.info("network.route_changed", "music route recovered");
                        return true;
                    }
                    return false;
                } catch (ApiException ignored) { /* Try the remaining directory candidates. */ }
            }
            return false;
        }
    }

    private String probeMusicServer(String baseUrl) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(new URL(baseUrl + API_PREFIX + "/sys/config"), "GET", false);
            boolean local = isLocalAddress(baseUrl);
            connection.setConnectTimeout(local ? 1800 : 7000);
            connection.setReadTimeout(local ? 2500 : 9000);
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readText(stream);
            JSONObject root = null;
            try { root = new JSONObject(body); } catch (Exception ignored) { }
            boolean musicEnvelope = root != null && root.has("code") && root.has("data")
                    && (root.has("msg") || root.has("message"));
            boolean success = status >= 200 && status < 300 && root != null && root.has("code")
                    && root.optInt("code", -1) == 0 && root.optJSONObject("data") != null;
            boolean recognizedAuthCode = root != null && (root.optInt("code", -1) == 120001
                    || musicEnvelope && LoginDiagnostics.isAuthenticationRejection(root.optInt("code", -1),
                    root.optString("msg", root.optString("message", ""))));
            boolean authRequired = (status == 401 || status == 403) && (musicEnvelope || recognizedAuthCode)
                    || (status >= 200 && status < 300 && recognizedAuthCode);
            if (success || authRequired) {
                // A protected config endpoint still proves the service can answer.
                // Authenticate once on that service, so credential failures are not retried across routes.
                String resolved = connection.getURL().toExternalForm();
                String suffix = API_PREFIX + "/sys/config";
                URL requested = new URL(baseUrl);
                URL reached = connection.getURL();
                if (!requested.getHost().equalsIgnoreCase(reached.getHost())
                        || ("https".equals(requested.getProtocol()) && !"https".equals(reached.getProtocol())))
                    throw new ApiException("音乐接口跳转到其他站点，请填写最终音乐服务地址");
                if (resolved.endsWith(suffix)) return resolved.substring(0, resolved.length() - suffix.length());
                throw new ApiException("音乐接口被重定向到其他页面");
            }
            if (status < 200 || status >= 300) throw new ApiException("音乐线路返回 HTTP " + status, status);
            if (musicEnvelope) {
                throw new ApiException("音乐服务配置请求失败（错误码 " + root.optInt("code", -1) + "）");
            }
            if (!connection.getURL().toExternalForm().equals(baseUrl + API_PREFIX + "/sys/config")) {
                throw new ApiException("FN Connect 跳转到网页，未直达音乐服务");
            }
            throw new ApiException("线路返回网页或非音乐接口数据");
        } catch (ApiException error) {
            throw error;
        } catch (IOException error) {
            ApiException failed = new ApiException(LoginDiagnostics.connectionHint(error, baseUrl));
            failed.initCause(error);
            throw failed;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public TrackPage getTracks(int page, int size, String query) throws Exception {
        String path;
        if (query == null || query.trim().length() == 0) {
            path = "/track/list?page=" + page + "&size=" + size;
        } else {
            path = "/search/track?q=" + URLEncoder.encode(query.trim(), "UTF-8")
                    + "&page=" + page + "&size=" + size;
        }
        Response response = execute(session.getBaseUrl(), path, "GET", null, true);
        JSONObject root = parseRoot(response.body);
        ensureBusinessOk(root, "获取歌曲失败");
        JSONObject data = root.optJSONObject("data");
        TrackPage result = new TrackPage();
        if (data == null) return result;

        result.total = data.optInt("total", 0);
        JSONArray list = data.optJSONArray("list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                Track track = Track.fromJson(item);
                if (track.guid.length() > 0) result.tracks.add(track);
            }
        }
        return result;
    }

    public TrackPage getFavoriteTracks(int page, int size) throws Exception {
        return getTrackPage("/favorite-track/list?page=" + page + "&size=" + size,
                "获取收藏失败");
    }

    public TrackPage getCollectionTracks(String type, String guid, int page, int size) throws Exception {
        String path;
        if (MediaCollection.TYPE_ALBUM.equals(type)) {
            path = "/track/album-detail/list?albumGUID=" + Uri.encode(guid);
        } else if (MediaCollection.TYPE_ARTIST.equals(type)) {
            path = "/track/artist-detail/list?artistGUID=" + Uri.encode(guid);
        } else if (MediaCollection.TYPE_GENRE.equals(type)) {
            path = "/track/genre-detail/list?genreGUID=" + Uri.encode(guid);
        } else if (MediaCollection.TYPE_PLAYLIST.equals(type)) {
            path = "/track/playlist-detail/list?playlistGUID=" + Uri.encode(guid);
        } else {
            throw new ApiException("不支持的音乐分类");
        }
        path += "&page=" + page + "&size=" + size;
        return getTrackPage(path, "获取分类歌曲失败");
    }

    public MediaCollectionPage getCollections(String type, int page, int size) throws Exception {
        String endpoint;
        if (MediaCollection.TYPE_ALBUM.equals(type)) endpoint = "/album/list";
        else if (MediaCollection.TYPE_ARTIST.equals(type)) endpoint = "/artist/list";
        else if (MediaCollection.TYPE_GENRE.equals(type)) endpoint = "/genre/list";
        else if (MediaCollection.TYPE_PLAYLIST.equals(type)) endpoint = "/playlist/list";
        else throw new ApiException("不支持的音乐分类");

        Response response = execute(session.getBaseUrl(), endpoint + "?page=" + page + "&size=" + size,
                "GET", null, true);
        JSONObject root = parseRoot(response.body);
        ensureBusinessOk(root, "获取分类失败");
        JSONObject data = root.optJSONObject("data");
        MediaCollectionPage result = new MediaCollectionPage();
        if (data == null) return result;
        result.total = data.optInt("total", 0);
        JSONArray list = data.optJSONArray("list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                MediaCollection collection = MediaCollection.fromJson(item, type);
                if (collection.guid.length() > 0) result.items.add(collection);
            }
        }
        return result;
    }

    public void setFavorite(String trackGuid, boolean favorite) throws Exception {
        JSONObject body = new JSONObject();
        body.put("trackGUID", trackGuid);
        Response response = execute(session.getBaseUrl(), favorite
                        ? "/favorite-track/create" : "/favorite-track/delete",
                "POST", body.toString(), true);
        JSONObject root = parseRoot(response.body);
        ensureBusinessOk(root, favorite ? "收藏失败" : "取消收藏失败");
    }

    public String getLyric(String trackGuid) throws Exception {
        if (!session.isSnapshot()) return snapshot().getLyric(trackGuid);
        if (!session.hasSession()) throw new ApiException("请先登录");
        String server = session.getLoginAddress();
        if (FnConnectResolver.looksLikeFnIdInput(server)) server = "fnid:" + FnConnectResolver.normalizeFnId(server);
        else server = session.getBaseUrl();
        String key = new JSONArray().put(server).put(session.getUsername()).put(trackGuid).toString();
        File entry = LyricCache.entry(context, sha256(key));
        String cached = LyricCache.read(entry);
        if (!cached.isEmpty() && LyricCache.fresh(entry)) return cached;
        try {
            String result = fetchLyric(trackGuid);
            if (result.isEmpty()) entry.delete();
            else LyricCache.save(entry, result);
            return result;
        } catch (ApiException failed) {
            if (!cached.isEmpty() && (failed.getCause() instanceof IOException || failed.getStatusCode() >= 500
                    || (failed.getStatusCode() == 0 && isOffline() && session.hasSession()))) return cached;
            throw failed;
        }
    }

    private boolean isOffline() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo active = manager == null ? null : manager.getActiveNetworkInfo();
        return active == null || !active.isConnected();
    }

    private String fetchLyric(String trackGuid) throws Exception {
        Response response = execute(session.getBaseUrl(), "/lyric/list?trackGUID="
                + Uri.encode(trackGuid), "GET", null, true);
        JSONObject root = parseRoot(response.body);
        ensureBusinessOk(root, "获取歌词失败");
        JSONObject data = root.optJSONObject("data");
        if (data == null) return "";
        JSONArray list = data.optJSONArray("list");
        String preferred = data.optString("preferred", "");
        String fallback = "";
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject lyric = list.optJSONObject(i);
                if (lyric == null) continue;
                String content = lyric.optString("content", "");
                if (fallback.length() == 0) fallback = content;
                if (preferred.length() > 0 && preferred.equals(lyric.optString("guid", ""))) {
                    return content;
                }
            }
        }
        return fallback;
    }

    private TrackPage getTrackPage(String path, String fallback) throws Exception {
        Response response = execute(session.getBaseUrl(), path, "GET", null, true);
        JSONObject root = parseRoot(response.body);
        ensureBusinessOk(root, fallback);
        JSONObject data = root.optJSONObject("data");
        TrackPage result = new TrackPage();
        if (data == null) return result;
        result.total = data.optInt("total", 0);
        JSONArray list = data.optJSONArray("list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                Track track = Track.fromJson(item);
                if (track.guid.length() > 0) result.tracks.add(track);
            }
        }
        return result;
    }

    public String streamUrl(String guid) {
        return session.getBaseUrl() + API_PREFIX + "/track/stream?guid=" + Uri.encode(guid);
    }

    public String transcodePlaylistUrl(String guid) {
        return session.getBaseUrl() + API_PREFIX + "/track/hls/" + Uri.encode(guid) + "/preset.m3u8";
    }

    public void startTranscode(String guid, String codec) throws Exception {
        startTranscode(guid, codec, 192);
    }

    public void startTranscode(String guid, String codec, int bitrateKbps) throws Exception {
        if (!"mp3".equals(codec) && !"flac".equals(codec)) throw new ApiException("不支持的转码格式");
        JSONObject output = new JSONObject().put("codec", codec).put("channel", 2);
        if ("mp3".equals(codec) && bitrateKbps != 128 && bitrateKbps != 192
                && bitrateKbps != 256 && bitrateKbps != 320) throw new ApiException("不支持的 MP3 码率");
        // The NAS MP3 encoder consumes bits/second, not the UI's kilobits/second.
        output.put("bitrate", "mp3".equals(codec) ? bitrateKbps * 1000 : 320);
        JSONObject request = new JSONObject().put("guid", guid).put("output", output);
        JSONObject root = parseRoot(execute(session.getBaseUrl(), "/track/transcode", "POST",
                request.toString(), true, 25000).body);
        ensureBusinessOk(root, "NAS 转码失败");
        JSONObject data = root.optJSONObject("data");
        String status = data == null ? "" : data.optString("status", "");
        if (!"success".equals(status) && !"ready".equals(status)) {
            throw new ApiException("NAS 转码未就绪：" + (data == null ? status
                    : data.optString("errmsg", status)));
        }
    }

    public void transcodeHeartbeat(String guid, double positionSeconds) throws Exception {
        JSONObject request = new JSONObject().put("guid", guid).put("timestamp", positionSeconds);
        JSONObject root = parseRoot(execute(session.getBaseUrl(), "/track/transcode/heartbeat", "POST",
                request.toString(), true, 3000).body);
        ensureBusinessOk(root, "转码会话已过期");
    }

    public void quitTranscode(String guid) throws Exception {
        JSONObject request = new JSONObject().put("guid", guid);
        JSONObject root = parseRoot(execute(session.getBaseUrl(), "/track/transcode/quit", "POST",
                request.toString(), true, 3000).body);
        ensureBusinessOk(root, "结束转码失败");
    }

    /** Authenticated HLS resources stay on the configured NAS origin; never export its cookie. */
    public byte[] readMusicResource(String resourceUrl, int limit) throws Exception {
        return readMusicResource(resourceUrl, limit, 15000);
    }

    public byte[] readMusicResource(String resourceUrl, int limit, int timeoutMs) throws Exception {
        return readMusicResource(resourceUrl, limit, timeoutMs, timeoutMs);
    }

    public byte[] readMusicResource(String resourceUrl, int limit, int timeoutMs, long budgetMs) throws Exception {
        URL base = new URL(session.getBaseUrl());
        URL url = new URL(resourceUrl);
        if (!base.getProtocol().equalsIgnoreCase(url.getProtocol())
                || !base.getHost().equalsIgnoreCase(url.getHost())
                || (base.getPort() < 0 ? base.getDefaultPort() : base.getPort())
                        != (url.getPort() < 0 ? url.getDefaultPort() : url.getPort())) {
            throw new ApiException("转码资源指向其他服务器，已拒绝发送登录信息");
        }
        HttpURLConnection connection = openConnection(url, "GET", true);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(Math.max(1, Math.min(8000, timeoutMs)));
        connection.setReadTimeout(Math.max(1, timeoutMs));
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        boolean consumed = false;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1L, budgetMs));
        try {
            int status = connection.getResponseCode();
            if (status == 401) {
                session.clearToken();
                throw new ApiException("登录已失效，请重新登录", status);
            }
            if (status == 403) throw new ApiException("没有权限读取转码资源：HTTP 403", status);
            if (status != 200) throw new ApiException("读取转码资源失败：HTTP " + status, status);
            long length = contentLength(connection);
            if (length > limit) throw new ApiException("转码分片超出大小限制");
            InputStream input = connection.getInputStream();
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
                    long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (remainingMs <= 0L) throw new SocketTimeoutException("读取转码资源超时");
                    connection.setReadTimeout((int) Math.max(1L, Math.min(timeoutMs, remainingMs)));
                    count = input.read(buffer);
                    if (count == -1) break;
                    if (output.size() + count > limit) throw new ApiException("转码分片超出大小限制");
                    output.write(buffer, 0, count);
                }
                if (length >= 0 && output.size() != length) throw new IOException("转码分片响应提前结束");
                consumed = true;
                return output.toByteArray();
            } finally { input.close(); }
        } finally {
            // Close the drained body and leave its socket available for the next HLS request.
            // Reserve disconnect() for incomplete responses across platform implementations.
            if (!consumed) connection.disconnect();
        }
    }

    public String coverUrl(String coverId, int size) {
        return session.getBaseUrl() + API_PREFIX + "/static/cover?coverId="
                + Uri.encode(coverId) + "&size=" + size;
    }

    public String cookieHeader() {
        return "music-token=" + session.getToken();
    }

    /** Opens an authenticated original stream for downloads or the legacy loopback player. */
    public HttpURLConnection openTrackStream(String guid, String range) throws Exception {
        if (!session.isSnapshot()) return snapshot().openTrackStream(guid, range);
        String base = currentMusicBase();
        try { return openTrackStreamAt(base, guid, range); }
        catch (IOException error) {
            if (recoverRoute(base)) return openTrackStreamAt(session.getBaseUrl(), guid, range);
            throw error;
        }
    }

    private HttpURLConnection openTrackStreamAt(String base, String guid, String range) throws Exception {
        HttpURLConnection connection = openConnection(new URL(base + API_PREFIX + "/track/stream?guid=" + Uri.encode(guid)), "GET", true);
        boolean opened = false;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", "audio/*,application/octet-stream");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (range != null && range.matches("bytes=\\d*-\\d*")) connection.setRequestProperty("Range", range);
            int status = connection.getResponseCode();
            if (status == 401) { session.clearToken(); throw new ApiException("登录已失效，请重新登录", status); }
            if (status == 403) throw new ApiException("没有权限读取原始音频", status);
            if (status != 200 && status != 206 && status != 416) {
                throw new ApiException("读取原始音频失败：HTTP " + status, status);
            }
            opened = true;
            return connection;
        } finally { if (!opened) connection.disconnect(); }
    }

    public Bitmap downloadCover(String coverId, int size) throws IOException {
        if (coverId == null || coverId.length() == 0) return null;
        IOException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("封面加载已取消");
            try { return readCover(coverId, size); }
            catch (IOException error) {
                if (error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)) throw error;
                failure = error;
            }
        }
        throw failure;
    }

    private Bitmap readCover(String coverId, int size) throws IOException {
        HttpURLConnection connection = null;
        InputStream input = null;
        File image = null;
        boolean consumed = false;
        try {
            connection = openConnection(new URL(coverUrl(coverId, size)), "GET", true);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;
            int limit = 8 * 1024 * 1024;
            long length = contentLength(connection);
            if (length > limit) throw new IOException("封面图片超出大小限制");
            input = connection.getInputStream();
            image = File.createTempFile("cover-", ".image", context.getCacheDir());
            FileOutputStream bytes = new FileOutputStream(image);
            byte[] buffer = new byte[8192];
            int count, received = 0;
            try {
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("封面加载已取消");
                    received += count;
                    if (received > limit) throw new IOException("封面图片超出大小限制");
                    bytes.write(buffer, 0, count);
                }
            } finally { bytes.close(); }
            if (length >= 0 && received != length) throw new IOException("封面图片响应提前结束");
            consumed = true;
            // Some platform decoders accept a truncated network stream as a partial image.
            // Validate the complete response before decoding or caching it.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("封面图片无效");
            int largest = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            int decodeSize = Math.max(32, Math.min(size, Runtime.getRuntime().maxMemory() <= 96L * 1024 * 1024 ? 384 : 1024));
            while (largest / (options.inSampleSize * 2) >= decodeSize) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap result = BitmapFactory.decodeFile(image.getAbsolutePath(), options);
            if (result == null) throw new IOException("封面图片无法解码");
            return result;
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (connection != null && !consumed) connection.disconnect();
            if (image != null) image.delete();
        }
    }

    public void downloadTrack(String guid, File outputFile) throws Exception {
        downloadTrack(guid, outputFile, null);
    }

    public void downloadTrack(String guid, File outputFile, TrackBufferListener listener) throws Exception {
        long expectedTotal = -1L;
        IOException lastIoError = null;

        for (int attempt = 1; attempt <= MAX_TRACK_DOWNLOAD_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
            long offset = outputFile.isFile() ? outputFile.length() : 0L;
            HttpURLConnection connection = null;
            InputStream input = null;
            FileOutputStream output = null;
            try {
                connection = openTrackStream(guid, offset > 0L ? "bytes=" + offset + "-" : null);

                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                        || status == HttpURLConnection.HTTP_FORBIDDEN) {
                    session.clearToken();
                    throw new ApiException("登录已失效，请重新登录", status);
                }
                if (status == 416 && expectedTotal > 0L && offset >= expectedTotal) {
                    return;
                }
                if (status != HttpURLConnection.HTTP_OK
                        && status != HttpURLConnection.HTTP_PARTIAL) {
                    if (status == 404) throw new ApiException("原始音频不存在或访问路径不可用（HTTP 404），请刷新曲库并检查音乐服务地址", status);
                    throw new ApiException("下载音频失败：HTTP " + status, status);
                }

                boolean append = offset > 0L && status == HttpURLConnection.HTTP_PARTIAL;
                if (!append && offset > 0L) {
                    if (listener != null) throw new IOException("服务器无法续传正在播放的音频，请重试");
                    offset = 0L;
                }
                long responseLength = contentLength(connection);
                long totalFromRange = totalFromContentRange(connection.getHeaderField("Content-Range"));
                if (totalFromRange > 0L) expectedTotal = totalFromRange;
                else if (status == HttpURLConnection.HTTP_OK && responseLength >= 0L) {
                    expectedTotal = responseLength;
                } else if (append && responseLength >= 0L) {
                    expectedTotal = offset + responseLength;
                }

                Log.i(TAG, "Track attempt " + attempt + ", HTTP " + status
                        + ", offset=" + offset + ", expected=" + expectedTotal);
                input = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
                output = new FileOutputStream(outputFile, append);
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("下载已取消");
                    }
                    output.write(buffer, 0, count);
                    if (listener != null) listener.onBufferReady(outputFile.length(), expectedTotal);
                }
                output.flush();

                long downloaded = outputFile.length();
                if (expectedTotal <= 0L || downloaded == expectedTotal) {
                    Log.i(TAG, "Track download complete, bytes=" + downloaded
                            + ", attempts=" + attempt);
                    return;
                }
                lastIoError = new IOException("响应提前结束：" + downloaded + "/" + expectedTotal);
                Log.w(TAG, lastIoError.getMessage());
            } catch (ApiException error) {
                throw error;
            } catch (InterruptedIOException error) {
                throw error;
            } catch (IOException error) {
                lastIoError = error;
                Log.w(TAG, "Track attempt " + attempt + " interrupted at "
                        + outputFile.length() + " bytes: " + safeMessage(error));
            } finally {
                if (output != null) try { output.close(); } catch (IOException ignored) { }
                if (input != null) try { input.close(); } catch (IOException ignored) { }
                if (connection != null) connection.disconnect();
            }

            if (attempt < MAX_TRACK_DOWNLOAD_ATTEMPTS) {
                try {
                    Thread.sleep(Math.min(3000L, 300L * attempt));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("下载已取消");
                }
            }
        }
        throw new ApiException("下载音频失败：已重试 " + MAX_TRACK_DOWNLOAD_ATTEMPTS
                + " 次，最后错误为 " + safeMessage(lastIoError));
    }

    private static long contentLength(HttpURLConnection connection) {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long totalFromContentRange(String value) {
        if (value == null) return -1L;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length()) return -1L;
        String total = value.substring(slash + 1).trim();
        if ("*".equals(total)) return -1L;
        try {
            return Long.parseLong(total);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String directLoginBase(String input) throws ApiException {
        String base = normalizeBaseUrl(input);
        // A bare DDNS name can front HTTPS on 443 instead of the historical HTTP port.
        // Probe with an anonymous GET. Only the chosen service receives the login POST.
        if (!input.matches("[a-zA-Z0-9.-]+") || !input.contains(".") || input.matches("[0-9.]+")
                || !base.startsWith("http://")) return base;
        return selectBareDomainRoute(base, "https://" + input);
    }

    String selectBareDomainRoute(String legacy, String secure) throws ApiException {
        try {
            return probeMusicServer(legacy);
        } catch (ApiException legacyFailure) {
            try {
                return probeMusicServer(secure);
            } catch (ApiException secureFailure) {
                // Some older music servers have password-login but no config endpoint.
                if (legacyFailure.getStatusCode() == 404 && secureFailure.getCause() instanceof java.net.ConnectException)
                    return legacy;
                throw new ApiException("域名直连失败。HTTP 5666：" + legacyFailure.getMessage()
                        + "。HTTPS 443：" + secureFailure.getMessage() + "。如使用其他端口，请填写完整地址");
            }
        }
    }

    public static String normalizeBaseUrl(String input) throws ApiException {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0) throw new ApiException("请输入 NAS 地址");
        boolean explicitScheme = value.matches("(?i)^https?://.*");
        if (!explicitScheme && value.contains("://")) throw new ApiException("NAS 地址请使用 HTTP 或 HTTPS");
        try {
            java.net.URI uri = new java.net.URI(explicitScheme ? value : "http://" + value);
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new ApiException("NAS 地址格式不正确，请检查域名和端口");
            int port = uri.getPort();
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.US);
            if (!explicitScheme) {
                if (port == 443 || port == 5667 || (port == -1 && !FnRelaySession.directoryReferer(new URL("https://" + host)).isEmpty())) scheme = "https";
                else if (port == -1) port = 5666;
            }
            String path = uri.getRawPath().replaceFirst("/+$", "").replaceFirst("(?i)/music(?:/api/v1)?$", "");
            return scheme + "://" + host + (port < 0 ? "" : ":" + port) + path;
        } catch (java.net.URISyntaxException | java.net.MalformedURLException invalid) {
            throw new ApiException("NAS 地址格式不正确，请检查域名和端口");
        }
    }

    private Response execute(String baseUrl, String path, String method, String body, boolean auth)
            throws Exception {
        return execute(baseUrl, path, method, body, auth, 15000);
    }

    private Response execute(String baseUrl, String path, String method, String body, boolean auth, int timeout)
            throws Exception {
        if (auth && !session.isSnapshot()) return snapshot().execute(baseUrl, path, method, body, true, timeout);
        String base = auth ? currentMusicBase() : baseUrl;
        try { return executeOnce(base, path, method, body, auth, timeout); }
        catch (ApiException error) {
            // Reads may be replayed after a route change; never replay a write with an unknown outcome.
            if (auth && "GET".equals(method) && error.getCause() instanceof IOException && recoverRoute(base)) {
                return executeOnce(session.getBaseUrl(), path, method, body, true, timeout);
            }
            throw error;
        }
    }

    private Response executeOnce(String baseUrl, String path, String method, String body, boolean auth, int timeout)
            throws Exception {
        if (baseUrl == null || baseUrl.length() == 0) throw new ApiException("尚未连接飞牛服务器");
        URL url = new URL(baseUrl + API_PREFIX + path);
        HttpURLConnection connection = null;
        boolean consumed = false;
        try {
            connection = openConnection(url, method, auth);
            connection.setReadTimeout(timeout);
            connection.setConnectTimeout(Math.min(8000, timeout));
            // Do not send a password-login POST through an unexpected redirect to a web login/directory.
            if (!auth) connection.setInstanceFollowRedirects(false);
            if (body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                connection.setDoOutput(true);
                // A buffered login body preserves the server's 401 error envelope on HttpURLConnection.
                if (auth) connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream output = connection.getOutputStream();
                output.write(bytes);
                output.flush();
                output.close();
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = stream == null ? "" : readText(stream);
            consumed = status >= 200 && status < 300;
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    || status == HttpURLConnection.HTTP_FORBIDDEN) {
                if (auth) {
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED) session.clearToken();
                    throw new ApiException(status == HttpURLConnection.HTTP_UNAUTHORIZED
                            ? "登录已失效，请重新登录" : "没有权限访问飞牛音乐", status);
                }
                JSONObject rejected = null;
                try { rejected = new JSONObject(responseBody); } catch (Exception ignored) { }
                throw new ApiException(LoginDiagnostics.loginFailure(status,
                        rejected == null ? 0 : rejected.optInt("code", 0),
                        rejected == null ? "" : rejected.optString("msg", rejected.optString("message", ""))), status);
            }
            if (!auth && status >= 300 && status < 400) {
                throw new ApiException("登录接口跳转到其他页面，请填写可直接访问飞牛音乐的地址", status);
            }
            if (status < 200 || status >= 300) {
                if (!auth && status == 404)
                    throw new ApiException("地址可访问，但没有找到飞牛音乐登录接口（HTTP 404）。请检查音乐应用和反向代理路径", status);
                if (status == 404 && "/track/transcode".equals(path))
                    throw new ApiException("NAS 转码接口返回 HTTP 404，请检查音乐服务版本和访问地址的转码接口转发", status);
                throw new ApiException("服务器返回 HTTP " + status, status);
            }
            return new Response(status, responseBody);
        } catch (ApiException error) {
            throw error;
        } catch (IOException error) {
            ApiException failure = new ApiException("无法连接服务器：" + (auth ? safeMessage(error) : LoginDiagnostics.connectionHint(error, baseUrl)));
            failure.initCause(error);
            throw failure;
        } finally {
            if (connection != null && !consumed) connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(URL url, String method, boolean auth) throws IOException {
        relaySession.prepare(url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        TlsCompat.configure(connection);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestMethod(method);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "FeiniuCarMusic/0.3.6 Android");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String cookies = relaySession.cookieHeader(url);
        if (auth && session.getToken().length() > 0) {
            if (cookies.length() > 0) cookies += "; ";
            cookies += cookieHeader();
        }
        if (cookies.length() > 0) connection.setRequestProperty("Cookie", cookies);
        return connection;
    }

    private static JSONObject parseRoot(String body) throws ApiException {
        try {
            return new JSONObject(body == null ? "" : body);
        } catch (Exception error) {
            throw new ApiException("服务器返回的数据无法解析");
        }
    }

    private static void ensureBusinessOk(JSONObject root, String fallback) throws ApiException {
        if (root.has("code") && root.optInt("code", -1) != 0) {
            String message = root.optString("msg", root.optString("message", fallback));
            throw new ApiException(message.length() == 0 ? fallback : message);
        }
    }

    private static String readText(InputStream input) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) result.append(buffer, 0, count);
        reader.close();
        return result.toString();
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }

    private static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
