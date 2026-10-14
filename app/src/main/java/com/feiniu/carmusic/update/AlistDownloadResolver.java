package com.feiniu.carmusic.update;

import com.feiniu.carmusic.data.TlsCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;

/** Obtains fresh file signatures as a guest, without embedding an AList account or token. */
final class AlistDownloadResolver {
    private static final String DIRECT_PREFIX = "/d/";
    private static final String UPDATE_DIRECTORY = "/feiniu-updates/";
    private static final int RESPONSE_LIMIT = 64 * 1024;

    private AlistDownloadResolver() { }

    static String resolve(String value) throws Exception {
        URL direct = new URL(value);
        if (!direct.getPath().startsWith(DIRECT_PREFIX) || hasSignature(direct.getQuery())) return value;
        String fullPath = URLDecoder.decode(direct.getPath().substring(2).replace("+", "%2B"), "UTF-8");
        int directoryStart = fullPath.lastIndexOf(UPDATE_DIRECTORY);
        if (directoryStart < 0) return value;
        String guestPath = fullPath.substring(directoryStart + UPDATE_DIRECTORY.length() - 1);
        URL endpoint = new URL(direct.getProtocol(), direct.getHost(), direct.getPort(), "/api/fs/get");
        JSONObject root = lookup(endpoint, guestPath);
        int code = root.optInt("code", -1);
        // With a root-based guest, use the complete storage path; with a restricted guest, use its relative path.
        if (code == 500 || code == 404 || code == 403) root = lookup(endpoint, fullPath);
        code = root.optInt("code", -1);
        if (code == 401) throw new IllegalStateException("更新网盘的访客访问尚未启用，请联系开发者");
        if (code == 403) throw new IllegalStateException("更新目录未开放访客读取权限，请联系开发者");
        if (code != 200) throw new IllegalStateException("更新网盘无法读取文件，请确认清单和安装包已上传（错误码 " + code + "）");
        JSONObject data = root.optJSONObject("data");
        if (data == null || data.optBoolean("is_dir", false)) throw new IllegalArgumentException("更新地址不是可下载的文件");
        String sign = data.optString("sign", "");
        if (sign.length() == 0) return value;
        String separator = direct.getQuery() == null || direct.getQuery().length() == 0 ? "?" : "&";
        return value + separator + "sign=" + URLEncoder.encode(sign, "UTF-8");
    }

    private static boolean hasSignature(String query) {
        if (query == null) return false;
        for (String part : query.split("&")) if (part.startsWith("sign=") && part.length() > 5) return true;
        return false;
    }

    private static JSONObject lookup(URL endpoint, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        InputStream input = null;
        try {
            TlsCompat.configure(connection);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            JSONObject body = new JSONObject().put("path", path).put("password", "");
            // refresh avoids stale cached metadata when update.json has been replaced.
            body.put("refresh", true);
            byte[] bytes = body.toString().getBytes("UTF-8");
            OutputStream output = connection.getOutputStream();
            try { output.write(bytes); } finally { output.close(); }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) return new JSONObject().put("code", status);
            if (status < 200 || status >= 300) throw new IllegalStateException("更新网盘接口返回 HTTP " + status);
            input = connection.getInputStream();
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (text.size() + count > RESPONSE_LIMIT) throw new IllegalArgumentException("更新网盘返回数据过大");
                text.write(buffer, 0, count);
            }
            return new JSONObject(new String(text.toByteArray(), "UTF-8"));
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            connection.disconnect();
        }
    }
}
