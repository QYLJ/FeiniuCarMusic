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
                    + "）。请核对音乐账号密码；新版 NAS 账号可能需要网页授权登录";
        }
        if (httpStatus == 403) {
            return "服务器拒绝登录访问（HTTP 403），请检查账号的音乐应用权限或远程访问限制";
        }
        if (code == 120002) return "音乐账号已被禁用，请联系 NAS 管理员（错误码 120002）";
        String text = message == null ? "" : message.trim();
        if (text.length() == 0) text = "服务器拒绝登录";
        return "登录失败：" + text + "（" + detail + "）";
    }

    public static String networkFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) return "HTTPS 证书或 TLS 校验失败，请检查设备时间和服务器证书";
            if (cause instanceof UnknownHostException) return "域名解析失败";
            if (cause instanceof SocketTimeoutException) return "连接超时";
            if (cause instanceof ConnectException) return "连接被拒绝或网络不可达";
        }
        // Some platform exception messages contain the full URL; do not display them.
        return "网络连接失败";
    }
}
