package com.feiniu.carmusic.ui;

final class TimedLyricLine {
    final int timeMs;
    final String text;

    TimedLyricLine(int timeMs, String text) {
        this.timeMs = timeMs;
        this.text = text == null ? "" : text.trim();
    }
}
