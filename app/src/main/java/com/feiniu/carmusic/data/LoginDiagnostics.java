package com.feiniu.carmusic.data;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

/** User-facing login diagnostics without exposing addresses, credentials or response bodies. */
public final class LoginDiagnostics {
    private LoginDiagnostics() { }

    public static boolean isAuthenticationRejection(int code, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return code == 120001 || lower.contains("unauthorized")
                || lower.contains("invalid token") || lower.contains("invalid-token");
    }

    public static String loginFailure(int httpStatus, int code, String message) {
        String detail = code != 0 ? "错误码 " + code : "HTTP " + httpStatus;
        if (httpStatus == 401 || isAuthenticationRejection(code, message)) {
            return "已连接音乐服务，但登录鉴权被拒绝（" + detail
                    + "）。请使用飞牛音乐密码登录对应的音乐账号密码。若平时使用 NAS 网页授权，请先确认音乐端已设置可用的密码账号";
        }
        if (httpStatus == 403) {
            return "服务器拒绝登录访问（HTTP 403），请检查账号的音乐应用权限或远程访问限制";
        }
        if (code == 120002) return "音乐账号已被禁用，请联系 NAS 管理员（错误码 120002）";
        String text = message == null ? "" : message.trim();
        if (text.length() == 0) text = "服务器拒绝登录";
        return "登录失败：" + text + "（" + detail + "）";
    }

    // Android 4–8 often embeds the destination and source address in one message.
    // Inspect only the peer preceding "(port ...)", never the caller following "from".
    private static final java.util.regex.Pattern PEER = java.util.regex.Pattern.compile(
            "(?i)(?:connect to|connecting to)\\s+(?:[^/\\s]+)?/(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*\\(port");

    public static String connectionHint(Throwable error, String baseUrl) {
        String reason = networkFailure(error);
        if ("域名解析失败".equals(reason)) return "域名解析失败。请在车机浏览器打开同一域名，检查拼写、Wi-Fi 和 DNS 设置";
        boolean privateTarget = FnApiClient.isLocalAddress(baseUrl);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException) {
                java.util.regex.Matcher match = PEER.matcher(String.valueOf(cause.getMessage()));
                if (match.find()) privateTarget |= FnApiClient.isLocalAddress("http://" + match.group(1));
                if (privateTarget) return reason + "。目标是内网地址，请确认车机与 NAS 在同一可达网络，或使用 FN ID / 公网地址";
                return reason + "。请核对地址、端口及路由器端口转发";
            }
        }
        return reason;
    }

    public static String networkFailure(Throwable error) {
        boolean tls = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.US);
            if (cause instanceof java.security.cert.CertificateNotYetValidException || message.contains("not valid until")
                    || message.contains("not yet valid"))
                return "HTTPS 证书尚未生效，请开启车机自动日期和时间，校准后重试";
            if (cause instanceof java.security.cert.CertificateExpiredException || message.contains("certificate expired"))
                return "HTTPS 证书已过期，请核对车机时间；时间正确时请 NAS 管理员更新证书";
            if (cause instanceof javax.net.ssl.SSLPeerUnverifiedException && message.contains("hostname"))
                return "HTTPS 证书与访问域名不匹配，请使用证书对应的域名登录";
            if (message.contains("trust anchor") || message.contains("unable to find valid certification path"))
                return "车机不信任 HTTPS 证书链，请检查 NAS 或反向代理是否提供完整证书链";
            if (cause instanceof SSLException) tls = true;
        }
        if (tls && java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) < 2020)
            return "车机日期明显异常，无法验证 HTTPS 证书。请开启自动日期和时间后重试";
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) return "HTTPS 证书或 TLS 校验失败，请检查设备时间和服务器证书";
            if (cause instanceof UnknownHostException) return "域名解析失败";
            if (cause instanceof SocketTimeoutException) return "连接超时";
            if (cause instanceof ConnectException && String.valueOf(cause.getMessage()).matches("(?is).*(timed out|after [0-9]+ms).*")
                    && !String.valueOf(cause.getMessage()).matches("(?is).*(refused|unreachable|enetunreach).*")) return "连接超时";
            if (cause instanceof ConnectException) return "连接被拒绝或网络不可达";
        }
        // Some platform exception messages contain the full URL; do not display them.
        return "网络连接失败";
    }
}
