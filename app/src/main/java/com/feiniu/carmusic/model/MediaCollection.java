package com.feiniu.carmusic.model;

import org.json.JSONObject;

public final class MediaCollection {
    public static final String TYPE_ALBUM = "album";
    public static final String TYPE_ARTIST = "artist";
    public static final String TYPE_GENRE = "genre";
    public static final String TYPE_PLAYLIST = "playlist";

    public String guid = "";
    public String name = "";
    public String coverId = "";
    public int trackCount;
    public String type = "";

    public static MediaCollection fromJson(JSONObject json, String type) {
        MediaCollection item = new MediaCollection();
        item.type = type;
        item.guid = firstString(json, "guid", "albumGUID", "artistGUID", "genreGUID", "playlistGUID", "id");
        item.name = firstString(json, "name", "title");
        item.coverId = firstString(json, "coverId", "coverID", "cover");
        item.trackCount = firstInt(json, "trackCount", "songCount", "count");
        return item;
    }

    private static String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            String value = json.optString(key, "");
            if (value != null && value.length() > 0 && !"null".equals(value)) return value;
        }
        return "";
    }

    private static int firstInt(JSONObject json, String... keys) {
        for (String key : keys) {
            if (json.has(key)) return json.optInt(key, 0);
        }
        return 0;
    }
}
