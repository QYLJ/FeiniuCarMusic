package com.feiniu.carmusic.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Resolves a bare FN ID through the public FN Connect connection directory. */
public final class FnConnectResolver {
    private static final String TAG = "FnConnectResolver";
    private static final String DIRECTORY_PATH = "/api/v1/fn/con";
    private static final String[] DIRECTORY_ORIGINS = {
            "https://fnos.net",
            "https://5ddd.com",
            "https://trzznas.com"
    };
    private static final String AUTH_PREFIX = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String API_KEY = "zIGtkc3dqZnJpd29qZXJqa2w7c";
    private static final SecureRandom RANDOM = new SecureRandom();

    private FnConnectResolver() {
    }

    public static boolean looksLikeFnIdInput(String input) {
        String value = input == null ? "" : input.trim();
        return value.length() > 0
                && value.indexOf('.') < 0
                && value.indexOf(':') < 0
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && !value.matches(".*\\s+.*");
    }

    public static String normalizeFnId(String input) throws ApiException {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.US);
        if (!value.matches("[a-z][a-z0-9-]{5,29}")) {
            throw new ApiException("FN ID 需为 6–30 位小写字母、数字或短横线，并以字母开头");
        }
        if (value.endsWith("-") || value.contains("--")) {
            throw new ApiException("FN ID 不能以短横线结尾，也不能包含连续短横线");
        }
        return value;
    }

    public static List<String> resolveCandidates(String input) throws ApiException {
        String fnId = normalizeFnId(input);
        JSONObject data = lookup(fnId);
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        JSONObject ports = data.optJSONObject("port");
        int httpPort = ports == null ? 5666 : ports.optInt("httpPort", 5666);
        int httpsPort = ports == null ? 5667 : ports.optInt("httpsPort", 5667);

        addPrivateIpv4(candidates, data.optJSONArray("ipv4"), httpPort);
        addPrivateIpv6(candidates, data.optJSONArray("ipv6"), httpPort);
        addRelay(candidates, data.optJSONArray("fn"));
        addSecureHosts(candidates, data.optJSONArray("ddns"), httpsPort);
        addHttpHosts(candidates, data.optJSONArray("publicIpv4"), httpPort, false);
        addHttpHosts(candidates, data.optJSONArray("publicIpv6"), httpPort, true);

        if (candidates.isEmpty()) {
            throw new ApiException("FN ID 已找到，但 FN Connect 没有返回可用线路");
        }
        return new ArrayList<String>(candidates);
    }

    private static JSONObject lookup(String fnId) throws ApiException {
        Exception firstNetworkError = null;
        Exception lastNetworkError = null;
        for (String origin : DIRECTORY_ORIGINS) {
            try {
                return lookupAt(origin, fnId);
            } catch (DirectoryBusinessException error) {
                if (error.code == 3000006 || error.code == 3000037) {
                    throw new ApiException("FN ID 不存在或尚未开启 FN Connect");
                }
                throw new ApiException("FN Connect 目录查询失败（错误码 " + error.code
                        + "）：" + error.getMessage());
            } catch (Exception error) {
                if (firstNetworkError == null) firstNetworkError = error;
                lastNetworkError = error;
                Log.w(TAG, "FN Connect directory failed at " + origin, error);
            }
        }
        Exception usefulError = firstNetworkError == null ? lastNetworkError : firstNetworkError;
        String detail = usefulError == null ? "unknown" : safeMessage(usefulError);
        throw new ApiException("无法访问 FN Connect 服务：" + detail);
    }

    private static JSONObject lookupAt(String origin, String fnId) throws Exception {
        TlsCompat.initialize();
        JSONObject requestBody = new JSONObject();
        requestBody.put("fnId", fnId);
        String body = requestBody.toString();
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = String.format(Locale.US, "%06d", 100000 + RANDOM.nextInt(900000));
        String authSource = AUTH_PREFIX + "_" + DIRECTORY_PATH + "_" + nonce + "_"
                + timestamp + "_" + hash("MD5", body) + "_" + API_KEY;
        String authHeader = "nonce=" + nonce + "&timestamp=" + timestamp
                + "&sign=" + hash("MD5", authSource);
        String fnSign = hash("SHA-256", "trim_connect`" + fnId + "`" + timestamp + "`anna");

        HttpURLConnection connection = (HttpURLConnection) new URL(origin + DIRECTORY_PATH).openConnection();
        try {
            TlsCompat.configure(connection);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("User-Agent", "FeiniuCarMusic/0.3.6 Android");
            connection.setRequestProperty("authx", authHeader);
            connection.setRequestProperty("fn-sign", fnSign);
            byte[] bytes = body.getBytes("UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.flush();
            output.close();

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = stream == null ? "" : readText(stream);
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            JSONObject root = new JSONObject(responseBody);
            int code = root.optInt("code", -1);
            if (code != 0) {
                String message = root.optString("msg", "FN Connect 解析失败");
                throw new DirectoryBusinessException(code,
                        message.length() == 0 ? "FN Connect 解析失败" : message);
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) throw new IOException("missing data");
            return data;
        } finally {
            connection.disconnect();
        }
    }

    private static void addPrivateIpv4(LinkedHashSet<String> result, JSONArray values, int port) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String host = values.optString(i, "").trim();
            if (isPrivateIpv4(host)) result.add("http://" + host + ":" + port);
        }
    }

    private static void addPrivateIpv6(LinkedHashSet<String> result, JSONArray values, int port) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String host = values.optString(i, "").trim();
            String lower = host.toLowerCase(Locale.US);
            if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:")) {
                result.add("http://[" + host + "]:" + port);
            }
        }
    }

    private static void addRelay(LinkedHashSet<String> result, JSONArray values) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String host = cleanHost(values.optString(i, ""));
            if (host.length() > 0) result.add("https://" + host);
        }
    }

    private static void addSecureHosts(LinkedHashSet<String> result, JSONArray values, int port) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String host = cleanHost(values.optString(i, ""));
            if (host.length() == 0) continue;
            result.add("https://" + host + (hasExplicitPort(host) ? "" : ":" + port));
        }
    }

    private static void addHttpHosts(LinkedHashSet<String> result, JSONArray values, int port,
                                     boolean ipv6) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            String host = values.optString(i, "").trim();
            if (host.length() == 0) continue;
            result.add(ipv6 ? "http://[" + host + "]:" + port
                    : "http://" + host + ":" + port);
        }
    }

    private static boolean isPrivateIpv4(String host) {
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
            return true;
        }
        if (!host.startsWith("172.")) return false;
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int second = Integer.parseInt(parts[1]);
            return second >= 16 && second <= 31;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String cleanHost(String value) {
        String host = value == null ? "" : value.trim();
        host = host.replaceFirst("(?i)^https?://", "");
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host;
    }

    private static boolean hasExplicitPort(String host) {
        return host.matches(".*:\\d+$");
    }

    private static String hash(String algorithm, String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] bytes = digest.digest(value.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) hex.append(String.format(Locale.US, "%02x", item & 0xff));
        return hex.toString();
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static final class DirectoryBusinessException extends Exception {
        final int code;

        DirectoryBusinessException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
