package com.feiniu.carmusic.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

public final class SessionStore {
    private static final String PREFS = "fn_session";
    private final SharedPreferences prefs;

    public SessionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        return prefs.getString("base_url", "");
    }

    public String getToken() {
        return prefs.getString("token", "");
    }

    public String getUsername() {
        return prefs.getString("username", "");
    }

    public String getLoginAddress() {
        return prefs.getString("login_address", getBaseUrl());
    }

    public String getDeviceId() {
        String id = prefs.getString("device_id", "");
        if (id.length() == 0) {
            id = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString("device_id", id).commit();
        }
        return id;
    }

    public boolean hasSession() {
        return getBaseUrl().length() > 0 && getToken().length() > 0;
    }

    public void saveLogin(String baseUrl, String token, String username, String loginAddress) {
        prefs.edit()
                .putString("base_url", baseUrl)
                .putString("token", token)
                .putString("username", username)
                .putString("login_address", loginAddress)
                .commit();
    }

    public void clearToken() {
        prefs.edit().remove("token").commit();
    }

    public void logout() {
        prefs.edit().remove("token").remove("base_url").commit();
    }
}
