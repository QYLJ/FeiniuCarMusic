package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import java.io.IOException;

@TargetApi(23)
final class AudioDataSourceApi23 {
    private AudioDataSourceApi23() { }
    static void set(MediaPlayer player, final ProgressiveAudioFile audio) {
        player.setDataSource(new MediaDataSource() {
            @Override public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
                return audio.readAt(position, buffer, offset, size);
            }
            @Override public long getSize() { return audio.size(); }
            @Override public void close() throws IOException { audio.close(); }
        });
    }
}
