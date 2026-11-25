package com.feiniu.carmusic.player;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Only application-owned subdirectories are offered or cleaned. Never clean a volume root. */
public final class CacheDirectories {
    private CacheDirectories() { }
    public static File internal(Context context) { return new File(context.getCacheDir(), "tracks"); }
    public static String selected(Context context) {
        return context.getSharedPreferences("song_cache", 0).getString("directory", "");
    }
    public static File resolve(Context context) {
        String path = selected(context);
        if (!path.isEmpty()) {
            File candidate = new File(path);
            // Do not recreate a missing mount point when a removable device is unplugged.
            if (candidate.isDirectory() && candidate.canWrite()) return candidate;
        }
        File fallback = internal(context);
        if (!fallback.isDirectory()) fallback.mkdirs();
        return fallback;
    }
    public static File select(Context context, File parent) throws IOException {
        File directory = parent == null ? internal(context)
                : new File(parent.getCanonicalFile(), "FeiniuCarMusic-cache");
        verify(directory);
        if (!context.getSharedPreferences("song_cache", 0).edit()
                .putString("directory", parent == null ? "" : directory.getCanonicalPath()).commit())
            throw new IOException("无法保存缓存目录");
        return directory;
    }
    public static void verify(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建缓存目录，请确认存储已挂载并允许写入");
        File probe = File.createTempFile("write-check-", ".tmp", directory);
        try {
            FileOutputStream output = new FileOutputStream(probe);
            try { output.write(1); output.getFD().sync(); } finally { output.close(); }
        } finally { probe.delete(); }
    }
    public static List<File> candidates(Context context) {
        Map<String, File> found = new LinkedHashMap<String, File>();
        if (Build.VERSION.SDK_INT >= 19) {
            for (File dir : CacheDirectoriesApi19.external(context)) add(found, dir);
        } else add(found, context.getExternalFilesDir(null));
        File primary = Environment.getExternalStorageDirectory();
        add(found, appDirectory(primary, context));
        // Several older head units omit USB/TF volumes from getExternalFilesDirs().
        for (String root : new String[] {"/storage", "/mnt", "/mnt/usb_storage", "/mnt/media_rw"}) {
            File[] children = new File(root).listFiles();
            if (children == null) continue;
            for (File volume : children) {
                if (!volume.isDirectory() || volume.getName().equals("emulated")
                        || volume.getName().equals("self") || volume.getName().equals("obb")) continue;
                add(found, appDirectory(volume, context));
            }
        }
        return new ArrayList<File>(found.values());
    }
    private static File appDirectory(File volume, Context context) {
        return volume == null ? null : new File(volume, "Android/data/" + context.getPackageName() + "/files");
    }
    private static void add(Map<String, File> result, File directory) {
        if (directory == null) return;
        try { result.put(directory.getCanonicalPath(), directory); } catch (IOException ignored) { }
    }
}
