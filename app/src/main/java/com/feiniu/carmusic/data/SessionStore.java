package com.feiniu.carmusic.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;
import java.util.Map;

public final class SessionStore {
    private static final String PREFS = "fn_session";
    private final SharedPreferences prefs;
    private final SessionStore parent;
    private final Map<String, String> snapshot;

    public SessionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        parent = null;
        snapshot = null;
    }

    private SessionStore(SessionStore source) {
        prefs = source.prefs;
        parent = source.parent == null ? source : source.parent;
        snapshot = new java.util.concurrent.ConcurrentHashMap<String, String>();
        snapshot.put("base_url", source.getBaseUrl()); snapshot.put("token", source.getToken());
        snapshot.put("username", source.getUsername()); snapshot.put("login_address", source.getLoginAddress());
        snapshot.put("device_id", source.getDeviceId());
    }

    synchronized SessionStore snapshot() { return new SessionStore(this); }
    boolean isSnapshot() { return snapshot != null; }

    private String value(String key, String fallback) {
        if (snapshot == null) return prefs.getString(key, fallback);
        String value = snapshot.get(key);
        return value == null ? fallback : value;
    }

    public String getBaseUrl() {
        return value("base_url", "");
    }

    public String getToken() {
        return value("token", "");
    }

    public String getUsername() {
        return value("username", "");
    }

    public String getLoginAddress() {
        return value("login_address", getBaseUrl());
    }

    public String getDeviceId() {
        String id = value("device_id", "");
        if (id.length() == 0) {
            id = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("device_id", id).commit();
        }
        return id;
    }

    public boolean hasSession() {
        return getBaseUrl().length() > 0 && getToken().length() > 0;
    }

    public synchronized void saveLogin(String baseUrl, String token, String username, String loginAddress) {
        if (snapshot != null) {
            snapshot.put("base_url", baseUrl); snapshot.put("token", token);
            snapshot.put("username", username); snapshot.put("login_address", loginAddress);
            return;
        }
        prefs.edit()
                .putString("base_url", baseUrl)
                .putString("token", token)
                .putString("username", username)
                .putString("login_address", loginAddress)
                .commit();
    }

    synchronized boolean changeRoute(String previousBase, String expectedToken, String baseUrl) {
        if (!previousBase.equals(getBaseUrl()) || !expectedToken.equals(getToken()) || expectedToken.length() == 0) return false;
        if (snapshot != null) {
            snapshot.put("base_url", baseUrl);
            parent.changeRoute(previousBase, expectedToken, baseUrl);
            return true;
        }
        prefs.edit().putString("base_url", baseUrl).commit();
        return true;
    }

    public synchronized void clearToken() {
        if (snapshot != null) {
            parent.invalidateSnapshot(getToken(), getUsername(), getLoginAddress());
            snapshot.remove("token");
            return;
        }
        prefs.edit().remove("token").commit();
    }

    public synchronized void logout() {
        if (snapshot != null) { snapshot.remove("token"); snapshot.remove("base_url"); return; }
        prefs.edit().remove("token").remove("base_url").commit();
    }

    private synchronized void invalidateSnapshot(String token, String username, String login) {
        if (token.equals(getToken()) && username.equals(getUsername()) && login.equals(getLoginAddress())) clearToken();
    }
}
