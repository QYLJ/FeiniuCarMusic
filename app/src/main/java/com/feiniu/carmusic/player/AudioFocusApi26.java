package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

@TargetApi(26)
final class AudioFocusApi26 {
    private AudioFocusApi26() { }
    static Object create(AudioManager.OnAudioFocusChangeListener listener) {
        return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(listener).setAcceptsDelayedFocusGain(false).build();
    }
    static int request(AudioManager manager, Object request) {
        return manager.requestAudioFocus((AudioFocusRequest) request);
    }
    static void abandon(AudioManager manager, Object request) {
        manager.abandonAudioFocusRequest((AudioFocusRequest) request);
    }
}
