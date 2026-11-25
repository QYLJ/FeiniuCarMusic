package com.feiniu.carmusic.player;
import android.annotation.TargetApi;
import android.content.Context;
import java.io.File;
@TargetApi(19)
final class CacheDirectoriesApi19 {
    static File[] external(Context context) { return context.getExternalFilesDirs(null); }
}
