package com.feiniu.carmusic.player;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

final class MediaControlSessions {
    private MediaControlSessions() { }

    static MediaControlSession create(Context context, ComponentName receiver,
                                      MediaControlSession.Callback callback) {
        if (Build.VERSION.SDK_INT >= 21) {
            return new Api21MediaControlSession(context, receiver, callback);
        }
        return new LegacyMediaControlSession(context, receiver);
    }
}
