package com.feiniu.carmusic.player;

import java.io.File;

final class AudioFile {
    final File file;
    final String codec;
    final int sampleRate, bitDepth, channels;
    final int bitrateKbps;
    final boolean transcoded;
    AudioFile(File file, String codec, int sampleRate, int bitDepth, int channels, boolean transcoded) {
        this(file, codec, sampleRate, bitDepth, channels, transcoded, 0);
    }
    AudioFile(File file, String codec, int sampleRate, int bitDepth, int channels, boolean transcoded, int bitrateKbps) {
        this.file = file; this.codec = codec; this.sampleRate = sampleRate;
        this.bitDepth = bitDepth; this.channels = channels; this.transcoded = transcoded;
        this.bitrateKbps = bitrateKbps;
    }
    String description() {
        String text = transcoded ? "NAS 转码 · " : "原始 · ";
        text += codec.length() == 0 ? "音频" : codec.toUpperCase(java.util.Locale.US);
        if (bitrateKbps > 0 && codec.toLowerCase(java.util.Locale.US).contains("mp3")) text += " " + bitrateKbps + " kbps";
        if (bitDepth > 0 && (codec.toLowerCase(java.util.Locale.US).contains("flac")
                || codec.toLowerCase(java.util.Locale.US).contains("pcm"))) text += " " + bitDepth + "bit";
        if (sampleRate > 0) text += " / " + (sampleRate / 1000.0) + "kHz";
        if (channels > 0) text += " / " + channels + "声道";
        return text;
    }
}
