package com.feiniu.carmusic.feedback;

import com.feiniu.carmusic.data.TlsCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Uploads one already-encrypted evidence envelope to a write-only AList folder. */
public final class AlistFeedbackClient {
    private static final int MAX_RESPONSE = 32768;
    private final String uploadUrl;
    private final String uploadDirectory;

    public AlistFeedbackClient(String uploadUrl, String uploadDirectory) {
        this.uploadUrl = uploadUrl == null ? "" : uploadUrl.trim();
        this.uploadDirectory = normalizeDirectory(uploadDirectory);
    }

    public UploadReceipt upload(byte[] encrypted, String reportId) throws Exception {
        if (!isHttpUrl(uploadUrl)) throw new IllegalStateException("反馈上传地址尚未配置");
        if (encrypted == null || encrypted.length == 0) throw new IllegalArgumentException("反馈文件为空");
        String fileName = "FeiniuCarMusic-feedback-" + safeId(reportId) + ".fnfeedback";
        String remotePath = uploadDirectory + "/" + fileName;
        HttpURLConnection connection = (HttpURLConnection) new URL(uploadUrl).openConnection();
        OutputStream output = null;
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setFixedLengthStreamingMode(encrypted.length);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("As-Task", "false");
            connection.setRequestProperty("File-Path", encodePath(remotePath));
            connection.setRequestProperty("User-Agent", "FeiniuCarMusic-Feedback Android");
            TlsCompat.configure(connection);
            output = connection.getOutputStream();
            output.write(encrypted);
            output.flush();
            int httpCode = connection.getResponseCode();
            String body = read(connection, httpCode >= 200 && httpCode < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (httpCode < 200 || httpCode >= 300) {
                throw new IllegalStateException("反馈服务器返回 HTTP " + httpCode + responseMessage(body));
            }
            JSONObject response = new JSONObject(body);
            if (response.optInt("code", 0) != 200) {
                throw new IllegalStateException("反馈服务器拒绝上传" + responseMessage(body));
            }
            return new UploadReceipt(reportId, remotePath, encrypted.length, FeedbackCrypto.sha256(encrypted));
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
            connection.disconnect();
        }
    }

    private static String read(HttpURLConnection connection, InputStream input) throws Exception {
        if (input == null) return "";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_RESPONSE) throw new IllegalStateException("反馈服务器响应过大");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), "UTF-8");
        } finally {
            input.close();
        }
    }

    private static String responseMessage(String body) {
        try {
            String message = new JSONObject(body).optString("message", "").trim();
            return message.length() == 0 ? "" : "：" + message.replaceAll("[\\r\\n]+", " ");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String encodePath(String path) throws Exception {
        return URLEncoder.encode(path, "UTF-8").replace("+", "%20");
    }

    private static String normalizeDirectory(String value) {
        String path = value == null ? "" : value.trim();
        if (path.length() == 0) return "/feedback";
        if (!path.startsWith("/")) path = "/" + path;
        while (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static String safeId(String value) {
        String id = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
        if (id.length() < 8) throw new IllegalArgumentException("反馈编号无效");
        return id;
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }

    public static final class UploadReceipt {
        public final String reportId;
        public final String remotePath;
        public final int bytes;
        public final String sha256;

        UploadReceipt(String reportId, String remotePath, int bytes, String sha256) {
            this.reportId = reportId;
            this.remotePath = remotePath;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }
}
