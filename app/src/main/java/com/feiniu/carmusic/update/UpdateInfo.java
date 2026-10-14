package com.feiniu.carmusic.update;

final class UpdateInfo {
    final int versionCode;
    final String versionName;
    final String apkUrl;
    final String sha256;
    final String changelog;
    final int minSdk;

    UpdateInfo(int versionCode, String versionName, String apkUrl, String sha256,
               String changelog, int minSdk) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.changelog = changelog;
        this.minSdk = minSdk;
    }
}
