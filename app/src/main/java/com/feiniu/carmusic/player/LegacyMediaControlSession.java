package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Build;

import com.feiniu.carmusic.model.Track;

@TargetApi(14)
final class LegacyMediaControlSession implements MediaControlSession {
    private final AudioManager audioManager;
    private final ComponentName receiver;
    private final RemoteControlClient remoteControl;

    LegacyMediaControlSession(Context context, ComponentName receiver) {
        this.receiver = receiver;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Intent mediaButton = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, mediaButton, flags);
        remoteControl = new RemoteControlClient(pending);
        remoteControl.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                | RemoteControlClient.FLAG_KEY_MEDIA_STOP
                | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
        if (audioManager != null) {
            audioManager.registerMediaButtonEventReceiver(receiver);
            audioManager.registerRemoteControlClient(remoteControl);
        }
    }

    @Override
    public void update(Track track, boolean playing, boolean loading, long positionMs,
                       long durationMs) {
        int state = loading ? RemoteControlClient.PLAYSTATE_BUFFERING
                : playing ? RemoteControlClient.PLAYSTATE_PLAYING
                : RemoteControlClient.PLAYSTATE_PAUSED;
        if (Build.VERSION.SDK_INT >= 18) {
            remoteControl.setPlaybackState(state, Math.max(0L, positionMs), playing ? 1f : 0f);
        } else {
            remoteControl.setPlaybackState(state);
        }
        if (track == null) return;
        RemoteControlClient.MetadataEditor editor = remoteControl.editMetadata(true)
                .putString(MediaMetadataRetriever.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, track.album);
        if (durationMs > 0L) {
            editor.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, durationMs);
        }
        editor.apply();
    }

    @Override
    public void release() {
        if (audioManager != null) {
            audioManager.unregisterRemoteControlClient(remoteControl);
            audioManager.unregisterMediaButtonEventReceiver(receiver);
        }
    }
}
