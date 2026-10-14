package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;

@TargetApi(26)
final class ServiceStartApi26 {
    private ServiceStartApi26() {
    }

    static void start(Context context, Intent intent) {
        context.startForegroundService(intent);
    }
}
