package com.feiniu.carmusic.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.feiniu.carmusic.model.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class QueueStore {
    private static final String PREFS = "playback_queue";
    private final SharedPreferences prefs;

    QueueStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void save(List<Track> tracks, int index, int positionMs) {
        try {
            JSONArray array = new JSONArray();
            for (Track track : tracks) array.put(track.toJson());
            prefs.edit()
                    .putString("tracks", array.toString())
                    .putInt("index", index)
                    .putInt("position", positionMs)
                    .commit();
        } catch (Exception ignored) {
        }
    }

    RestoredQueue restore() {
        RestoredQueue result = new RestoredQueue();
        try {
            JSONArray array = new JSONArray(prefs.getString("tracks", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) result.tracks.add(Track.fromStoredJson(item));
            }
            result.index = prefs.getInt("index", result.tracks.isEmpty() ? -1 : 0);
            if (result.index >= result.tracks.size()) result.index = result.tracks.size() - 1;
            result.positionMs = prefs.getInt("position", 0);
        } catch (Exception ignored) {
            result.tracks.clear();
            result.index = -1;
            result.positionMs = 0;
        }
        return result;
    }

    void clear() {
        prefs.edit().clear().commit();
    }

    static final class RestoredQueue {
        final List<Track> tracks = new ArrayList<Track>();
        int index = -1;
        int positionMs;
    }
}
