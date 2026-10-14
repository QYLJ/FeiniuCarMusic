package com.feiniu.carmusic.data;

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keeps the FN Connect gateway routing session separate from music authentication. */
final class FnRelaySession {
    private final Map<String, CookieManager> gateways = new HashMap<String, CookieManager>();

    synchronized void prepare(URL url) throws IOException {
        String referer = directoryReferer(url);
        if (referer.length() == 0 || gateways.containsKey(origin(url))) return;
        bootstrap(new URL(origin(url) + "/music/"), referer);
    }

    // Package visibility lets local gateway tests exercise the same HTTP exchange.
    synchronized void bootstrap(URL entry, String referer) throws IOException {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        URL current = entry;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            try {
                TlsCompat.configure(connection);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(9000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Referer", referer);
                connection.setRequestProperty("User-Agent", "FeiniuCarMusic Android");
                connection.setRequestProperty("Accept", "text/html, */*");
                String cookie = cookieHeader(cookies, current);
                if (cookie.length() > 0) connection.setRequestProperty("Cookie", cookie);

                int status = connection.getResponseCode();
                cookies.put(uri(current), connection.getHeaderFields());
                if (status >= 200 && status < 300) {
                    gateways.put(origin(entry), cookies);
                    return;
                }
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("FN Connect 网关未返回跳转地址");
                    URL next = new URL(current, location);
                    // The gateway's routing redirect is to itself. Never follow it to a directory
                    // page or another origin, and never send music credentials in this exchange.
                    if (!origin(entry).equals(origin(next))) {
                        throw new IOException("FN Connect 网关跳转到其他站点");
                    }
                    current = next;
                    continue;
                }
                throw new IOException("FN Connect 网关返回 HTTP " + status);
            } finally {
                // Closing a response also releases the socket on Android 4.
                try {
                    InputStream stream = connection.getInputStream();
                    if (stream != null) stream.close();
                } catch (IOException ignored) { }
                connection.disconnect();
            }
        }
        throw new IOException("FN Connect 网关跳转次数过多");
    }

    synchronized String cookieHeader(URL url) throws IOException {
        CookieManager cookies = gateways.get(origin(url));
        return cookies == null ? "" : cookieHeader(cookies, url);
    }

    static String directoryReferer(URL url) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) return "";
        String host = url.getHost().toLowerCase(Locale.US);
        for (String domain : new String[] {"fnos.net", "5ddd.com", "trzznas.com"}) {
            if (host.endsWith("." + domain)) return "https://" + domain + "/";
        }
        return "";
    }

    private static String cookieHeader(CookieManager cookies, URL url) throws IOException {
        Map<String, List<String>> headers = cookies.get(uri(url),
                new HashMap<String, List<String>>());
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (!"Cookie".equalsIgnoreCase(header.getKey())) continue;
            for (String value : header.getValue()) {
                if (result.length() > 0) result.append("; ");
                result.append(value);
            }
        }
        return result.toString();
    }

    private static String origin(URL url) {
        int port = url.getPort() < 0 ? url.getDefaultPort() : url.getPort();
        return url.getProtocol().toLowerCase(Locale.US) + "://"
                + url.getHost().toLowerCase(Locale.US) + ":" + port;
    }

    private static URI uri(URL url) throws IOException {
        try { return url.toURI(); }
        catch (Exception error) { throw new IOException("FN Connect 网关地址格式错误", error); }
    }
}
