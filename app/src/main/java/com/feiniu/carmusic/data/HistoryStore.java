package com.feiniu.carmusic.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.feiniu.carmusic.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class HistoryStore {
    private static final String PREFS = "playback_history";
    private static final int MAX_ITEMS = 100;
    private final SharedPreferences prefs;

    public HistoryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void add(Track track) {
        if (track == null || track.guid.length() == 0) return;
        List<Track> current = list();
        for (int i = current.size() - 1; i >= 0; i--) {
            if (track.guid.equals(current.get(i).guid)) current.remove(i);
        }
        current.add(0, track);
        while (current.size() > MAX_ITEMS) current.remove(current.size() - 1);
        try {
            JSONArray array = new JSONArray();
            for (Track item : current) array.put(item.toJson());
            prefs.edit().putString("tracks", array.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    public synchronized List<Track> list() {
        List<Track> result = new ArrayList<Track>();
        try {
            JSONArray array = new JSONArray(prefs.getString("tracks", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json != null) result.add(Track.fromStoredJson(json));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public synchronized void clear() {
        prefs.edit().clear().commit();
    }
}
