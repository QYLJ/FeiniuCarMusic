package com.feiniu.carmusic.data;

import android.content.Context;
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
    private final FnRelaySession relaySession = new FnRelaySession();

    public interface TrackBufferListener {
        void onBufferReady(long downloadedBytes, long totalBytes) throws Exception;
    }

    private FnApiClient(Context context) {
        TlsCompat.initialize();
        session = new SessionStore(context);
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
        String loginAddress = serverInput == null ? "" : serverInput.trim();
        String baseUrl;
        if (FnConnectResolver.looksLikeFnIdInput(loginAddress)) {
            String fnId = FnConnectResolver.normalizeFnId(loginAddress);
            baseUrl = findReachableFnIdRoute(FnConnectResolver.resolveCandidates(fnId));
            loginAddress = fnId;
        } else {
            baseUrl = normalizeBaseUrl(loginAddress);
        }
        JSONObject request = new JSONObject();
        request.put("username", username);
        request.put("password", sha256(password == null ? "" : password));
        request.put("deviceId", session.getDeviceId());

        Response response = execute(baseUrl, "/user/password-login", "POST", request.toString(), false);
        JSONObject root = parseRoot(response.body);
        int code = root.optInt("code", 0);
        if (code != 0) {
            throw new ApiException(LoginDiagnostics.loginFailure(response.status, code,
                    root.optString("msg", root.optString("message", "登录失败"))));
        }
        JSONObject data = root.optJSONObject("data");
        String token = data == null ? "" : data.optString("userToken", "");
        if (token.length() == 0) throw new ApiException("登录失败：服务器未返回 Token");
        session.saveLogin(baseUrl, token, username, loginAddress);
    }

    private String findReachableFnIdRoute(List<String> candidates) throws ApiException {
        LinkedHashSet<String> failures = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            try {
                return probeMusicServer(candidate);
            } catch (ApiException error) {
                failures.add(error.getMessage());
            }
        }
        StringBuilder detail = new StringBuilder();
        for (String failure : failures) {
            if (detail.length() > 0) detail.append("；");
            detail.append(failure);
        }
        throw new ApiException("FN ID 已找到，但音乐线路均不可用：" + detail
                + "。请用同一网络的官方音乐页面检查访问");
    }

    private String probeMusicServer(String baseUrl) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(new URL(baseUrl + API_PREFIX + "/sys/config"), "GET", false);
            boolean local = baseUrl.startsWith("http://192.168.")
                    || baseUrl.startsWith("http://10.") || baseUrl.startsWith("http://172.")
                    || baseUrl.startsWith("http://[");
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
            boolean authRequired = musicEnvelope && (status == 401 || status == 403
                    || (status >= 200 && status < 300 && LoginDiagnostics.isAuthenticationRejection(
                    root.optInt("code", -1), root.optString("msg", root.optString("message", "")))));
            if (success || authRequired) {
                // A protected config endpoint still proves the service can answer.
                // Authenticate once on that service, so credential failures are not retried across routes.
                String resolved = connection.getURL().toExternalForm();
                String suffix = API_PREFIX + "/sys/config";
                if (resolved.endsWith(suffix)) return resolved.substring(0, resolved.length() - suffix.length());
                throw new ApiException("音乐接口被重定向到其他页面");
            }
            if (status < 200 || status >= 300) throw new ApiException("音乐线路返回 HTTP " + status);
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
            throw new ApiException(LoginDiagnostics.networkFailure(error));
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
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = connection.getResponseCode();
            if (status == 401) {
                session.clearToken();
                throw new ApiException("登录已失效，请重新登录", status);
            }
            if (status != 200) throw new ApiException("读取转码分片失败：HTTP " + status, status);
            long length = contentLength(connection);
            if (length > limit) throw new ApiException("转码分片超出大小限制");
            InputStream input = connection.getInputStream();
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
                    if (output.size() + count > limit) throw new ApiException("转码分片超出大小限制");
                    output.write(buffer, 0, count);
                }
                if (length >= 0 && output.size() != length) throw new IOException("转码分片响应提前结束");
                return output.toByteArray();
            } finally { input.close(); }
        } finally { connection.disconnect(); }
    }

    public String coverUrl(String coverId, int size) {
        return session.getBaseUrl() + API_PREFIX + "/static/cover?coverId="
                + Uri.encode(coverId) + "&size=" + size;
    }

    public String cookieHeader() {
        return "music-token=" + session.getToken();
    }

    public Bitmap downloadCover(String coverId, int size) throws IOException {
        if (coverId == null || coverId.length() == 0) return null;
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = openConnection(new URL(coverUrl(coverId, size)), "GET", true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return null;
            input = new BufferedInputStream(connection.getInputStream());
            return BitmapFactory.decodeStream(input);
        } finally {
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            if (connection != null) connection.disconnect();
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
                connection = openConnection(new URL(streamUrl(guid)), "GET", true);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept", "audio/*,application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (offset > 0L) connection.setRequestProperty("Range", "bytes=" + offset + "-");

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

    public static String normalizeBaseUrl(String input) throws ApiException {
        String value = input == null ? "" : input.trim();
        if (value.length() == 0) throw new ApiException("请输入 NAS 地址");
        value = value.replaceAll("(?i)/music/api/v1/?$", "");
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);

        boolean hasScheme = value.startsWith("http://") || value.startsWith("https://");
        if (!hasScheme) {
            boolean hasPort = value.matches(".*:\\d+$");
            value = "http://" + value + (hasPort ? "" : ":5666");
        }
        Uri uri = Uri.parse(value);
        if (uri.getHost() == null || uri.getHost().length() == 0) {
            throw new ApiException("NAS 地址格式不正确");
        }
        return value;
    }

    private Response execute(String baseUrl, String path, String method, String body, boolean auth)
            throws Exception {
        return execute(baseUrl, path, method, body, auth, 15000);
    }

    private Response execute(String baseUrl, String path, String method, String body, boolean auth, int timeout)
            throws Exception {
        if (baseUrl == null || baseUrl.length() == 0) throw new ApiException("尚未连接飞牛服务器");
        URL url = new URL(baseUrl + API_PREFIX + path);
        HttpURLConnection connection = null;
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
                throw new ApiException("服务器返回 HTTP " + status, status);
            }
            return new Response(status, responseBody);
        } catch (ApiException error) {
            throw error;
        } catch (IOException error) {
            throw new ApiException("无法连接服务器：" + safeMessage(error));
        } finally {
            if (connection != null) connection.disconnect();
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
        connection.setRequestProperty("Connection", "close");
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
