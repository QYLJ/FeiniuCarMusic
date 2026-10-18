package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

/** Keep the player's media/music attributes identical to those in its focus request. */
@TargetApi(21)
final class MediaPlayerAudioApi21 {
    private MediaPlayerAudioApi21() { }
    static AudioAttributes musicAttributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }
    static void configure(MediaPlayer player) { player.setAudioAttributes(musicAttributes()); }
}
