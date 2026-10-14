package com.feiniu.carmusic.update;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

@TargetApi(26)
final class UpdateInstallApi26 {
    static final int REQUEST_UNKNOWN_SOURCES = 4017;

    private UpdateInstallApi26() { }

    static boolean canInstall(Activity activity) {
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    static void requestPermission(Activity activity) {
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivityForResult(settings, REQUEST_UNKNOWN_SOURCES);
    }
}
