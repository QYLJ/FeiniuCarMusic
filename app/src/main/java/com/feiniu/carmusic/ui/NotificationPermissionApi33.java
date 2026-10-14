package com.feiniu.carmusic.ui;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.pm.PackageManager;

@TargetApi(33)
final class NotificationPermissionApi33 {
    private NotificationPermissionApi33() {
    }

    static void requestIfNeeded(Activity activity) {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7001);
        }
    }
}
