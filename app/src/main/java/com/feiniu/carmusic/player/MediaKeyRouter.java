package com.feiniu.carmusic.player;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.KeyEvent;

public final class MediaKeyRouter {
    private MediaKeyRouter() { }

    public static String actionFor(KeyEvent event) {
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN
                || event.getRepeatCount() != 0) return null;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return PlaybackService.ACTION_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return PlaybackService.ACTION_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return PlaybackService.ACTION_TOGGLE;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return PlaybackService.ACTION_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return PlaybackService.ACTION_PREVIOUS;
            default:
                return null;
        }
    }

    public static boolean dispatch(Context context, KeyEvent event) {
        String action = actionFor(event);
        if (action == null) return false;
        Intent service = new Intent(context, PlaybackService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) ServiceStartApi26.start(context, service);
        else context.startService(service);
        return true;
    }
}
