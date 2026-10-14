package com.feiniu.carmusic.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class Track {
    public String guid = "";
    public String title = "";
    public String artist = "";
    public String album = "";
    public String coverId = "";
    public long durationMs;
    public int accessStatus;
    public boolean favorite;
    public String codec = "";
    public String format = "";
    public int channels;
    public int bitDepth;
    public int sampleRate;
    public int bitrate;

    public static Track fromJson(JSONObject json) {
        Track track = new Track();
        track.guid = firstString(json, "guid", "trackGuid", "trackGUID", "id");
        track.title = firstString(json, "title", "name");
        track.coverId = firstString(json, "coverId", "coverID", "cover");
        track.durationMs = firstLong(json, "duration", "durationMs", "length");
        track.accessStatus = json.optInt("accessStatus", 0);
        track.favorite = json.optBoolean("isFavorite", json.optBoolean("favorite", false));
        JSONObject audioSpec = json.optJSONObject("audioSpec");
        if (audioSpec != null) {
            track.codec = firstString(audioSpec, "codec", "format", "container");
            track.format = firstString(audioSpec, "format", "container");
            track.channels = (int) firstLong(audioSpec, "channels", "channel", "channelCount");
            track.bitDepth = audioSpec.optInt("bitDepth", 0);
            track.sampleRate = audioSpec.optInt("sampleRate", 0);
            track.bitrate = audioSpec.optInt("bitrate", 0);
        }

        JSONObject albumObject = json.optJSONObject("album");
        track.album = albumObject == null
                ? json.optString("albumName", "")
                : firstString(albumObject, "name", "title");

        JSONArray artists = json.optJSONArray("artists");
        if (artists != null) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < artists.length(); i++) {
                Object item = artists.opt(i);
                String name = "";
                if (item instanceof JSONObject) {
                    name = firstString((JSONObject) item, "name", "title");
                } else if (item != null) {
                    name = String.valueOf(item);
                }
                if (name.length() > 0) {
                    if (names.length() > 0) names.append(" / ");
                    names.append(name);
                }
            }
            track.artist = names.toString();
        }
        if (track.artist.length() == 0) {
            track.artist = firstString(json, "artist", "artistName");
        }
        return track;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("guid", guid);
        json.put("title", title);
        json.put("artist", artist);
        json.put("album", album);
        json.put("coverId", coverId);
        json.put("durationMs", durationMs);
        json.put("accessStatus", accessStatus);
        json.put("favorite", favorite);
        json.put("codec", codec);
        json.put("format", format);
        json.put("channels", channels);
        json.put("bitDepth", bitDepth);
        json.put("sampleRate", sampleRate);
        json.put("bitrate", bitrate);
        return json;
    }

    public static Track fromStoredJson(JSONObject json) {
        Track track = new Track();
        track.guid = json.optString("guid", "");
        track.title = json.optString("title", "");
        track.artist = json.optString("artist", "");
        track.album = json.optString("album", "");
        track.coverId = json.optString("coverId", "");
        track.durationMs = json.optLong("durationMs", 0L);
        track.accessStatus = json.optInt("accessStatus", 0);
        track.favorite = json.optBoolean("favorite", false);
        track.codec = json.optString("codec", "");
        track.format = json.optString("format", "");
        track.channels = json.optInt("channels", 0);
        track.bitDepth = json.optInt("bitDepth", 0);
        track.sampleRate = json.optInt("sampleRate", 0);
        track.bitrate = json.optInt("bitrate", 0);
        return track;
    }

    public String qualityLabel() {
        StringBuilder label = new StringBuilder();
        if (codec.length() > 0) label.append(codec.toUpperCase(Locale.US));
        if (bitDepth > 0) {
            if (label.length() > 0) label.append(' ');
            label.append(bitDepth).append("bit");
        }
        if (sampleRate > 0) {
            if (label.length() > 0) label.append('/');
            if (sampleRate % 1000 == 0) label.append(sampleRate / 1000);
            else label.append(sampleRate / 1000.0f);
            label.append("kHz");
        }
        return label.length() == 0 ? "原始音质" : label.toString();
    }

    private static String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (value != null && value.length() > 0 && !"null".equals(value)) return value;
        }
        return "";
    }

    private static long firstLong(JSONObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key)) {
                long value = json.optLong(key, 0L);
                if (value > 0L) return value;
            }
        }
        return 0L;
    }
}
