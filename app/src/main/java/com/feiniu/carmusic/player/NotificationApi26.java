package com.feiniu.carmusic.player;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;

@TargetApi(26)
final class NotificationApi26 {
    private NotificationApi26() {
    }

    static void createChannel(Service service, String channelId) {
        NotificationManager manager = (NotificationManager)
                service.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                channelId, "音乐播放", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示当前播放歌曲和后台播放状态");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    static Notification.Builder newBuilder(Service service, String channelId) {
        return new Notification.Builder(service, channelId);
    }
}
