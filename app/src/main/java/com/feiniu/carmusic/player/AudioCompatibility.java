package com.feiniu.carmusic.player;

import com.feiniu.carmusic.model.Track;
import java.util.Locale;

public final class AudioCompatibility {
    public static final int AUTO = 0;
    public static final int ORIGINAL = 1;
    public static final int NAS_MP3 = 2;
    public static final int NAS_FLAC = 3;
    private AudioCompatibility() { }

    public static String transcodeCodec(Track track, int mode, int sdk) {
        if (mode == NAS_MP3) return "mp3";
        if (mode == NAS_FLAC) return "flac";
        if (mode == ORIGINAL) return "";
        String codec = track.codec.toLowerCase(Locale.US);
        String format = track.format.toLowerCase(Locale.US);
        if (track.channels > 2 || codec.matches(".*(alac|ape|dsd|dsf|dff|dts|truehd|mlp|wma|eac3|ac3).*")) return "mp3";
        if (codec.contains("opus") && (sdk < 21 || (sdk < 29 && (format.contains("mp4") || format.contains("m4a"))))) return "mp3";
        if (codec.contains("mp3") && sdk < 29 && (format.contains("mp4") || format.contains("m4a"))) return "mp3";
        if (codec.contains("flac") && sdk < 29 && (track.bitDepth > 16 || track.sampleRate > 48000
                || format.contains("mp4") || format.contains("m4a"))) return "mp3";
        if (codec.contains("pcm") && sdk < 21 && track.bitDepth > 16) return "mp3";
        return "";
    }

    public static String label(int mode) {
        if (mode == ORIGINAL) return "原始音质";
        if (mode == NAS_MP3) return "NAS 转码 MP3";
        if (mode == NAS_FLAC) return "NAS 转码 FLAC";
        return "自动兼容";
    }
}
