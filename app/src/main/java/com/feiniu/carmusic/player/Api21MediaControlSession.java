package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.feiniu.carmusic.model.Track;

@TargetApi(21)
final class Api21MediaControlSession implements MediaControlSession {
    private static final long ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_STOP
            | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_FAST_FORWARD
            | PlaybackState.ACTION_REWIND
            | PlaybackState.ACTION_SEEK_TO;

    private final MediaSession session;
    private final Callback callback;

    Api21MediaControlSession(Context context, ComponentName receiver, Callback callback) {
        this.callback = callback;
        session = new MediaSession(context.getApplicationContext(), "FeiniuCarMusic");
        Intent mediaButton = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        session.setMediaButtonReceiver(PendingIntent.getBroadcast(context, 0, mediaButton, flags));
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { Api21MediaControlSession.this.callback.onPlay(); }
            @Override public void onPause() { Api21MediaControlSession.this.callback.onPause(); }
            @Override public void onStop() { Api21MediaControlSession.this.callback.onPause(); }
            @Override public void onSkipToNext() { Api21MediaControlSession.this.callback.onNext(); }
            @Override public void onSkipToPrevious() { Api21MediaControlSession.this.callback.onPrevious(); }
            @Override public void onFastForward() { Api21MediaControlSession.this.callback.onNext(); }
            @Override public void onRewind() { Api21MediaControlSession.this.callback.onPrevious(); }
            @Override public void onSeekTo(long position) {
                Api21MediaControlSession.this.callback.onSeekTo(position);
            }

            @Override
            public boolean onMediaButtonEvent(Intent intent) {
                KeyEvent event = intent == null ? null
                        : (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                String action = MediaKeyRouter.actionFor(event);
                if (action == null) return super.onMediaButtonEvent(intent);
                dispatch(action);
                return true;
            }
        });
        session.setActive(true);
    }

    private void dispatch(String action) {
        if (PlaybackService.ACTION_PLAY.equals(action)) callback.onPlay();
        else if (PlaybackService.ACTION_PAUSE.equals(action)) callback.onPause();
        else if (PlaybackService.ACTION_TOGGLE.equals(action)) callback.onToggle();
        else if (PlaybackService.ACTION_NEXT.equals(action)) callback.onNext();
        else if (PlaybackService.ACTION_PREVIOUS.equals(action)) callback.onPrevious();
    }

    @Override
    public void update(Track track, boolean playing, boolean loading, long positionMs,
                       long durationMs) {
        int state = loading ? PlaybackState.STATE_BUFFERING
                : playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(ACTIONS)
                .setState(state, Math.max(0L, positionMs), playing ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build());
        if (track == null) {
            session.setMetadata(null);
            return;
        }
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album);
        if (durationMs > 0L) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        session.setMetadata(metadata.build());
    }

    @Override
    public void release() {
        session.setActive(false);
        session.release();
    }
}
