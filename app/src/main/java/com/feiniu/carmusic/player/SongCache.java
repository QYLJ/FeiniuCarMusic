package com.feiniu.carmusic.player;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Device-local disk cache policy shared by settings, downloads and the playback service. */
public final class SongCache {
    public static final int DEFAULT_LIMIT_MB = 512;
    public static final int MIN_LIMIT_MB = 64;
    public static final int MAX_LIMIT_MB = 65536;
    private static final long MB = 1024L * 1024L;
    private static final long FREE_SPACE_RESERVE = 64L * MB;
    private static final Object FILES = new Object();
    private static final Object CLEANUP = new Object();
    private static final Map<String, Integer> IN_USE = new HashMap<String, Integer>();
    private static final Set<String> PENDING_TRIMS = new HashSet<String>();
    private static final ExecutorService MAINTENANCE = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "SongCacheMaintenance");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final SharedPreferences prefs;
    private final File directory;

    public SongCache(Context context) {
        this(context, CacheDirectories.resolve(context));
    }

    SongCache(Context context, File directory) {
        prefs = context.getApplicationContext().getSharedPreferences("song_cache", Context.MODE_PRIVATE);
        this.directory = directory;
        if (directory.equals(CacheDirectories.internal(context)) && !directory.isDirectory()) directory.mkdirs();
    }

    public int limitMb() {
        int value = prefs.getInt("limit_mb", DEFAULT_LIMIT_MB);
        return value >= MIN_LIMIT_MB && value <= MAX_LIMIT_MB ? value : DEFAULT_LIMIT_MB;
    }

    public boolean setLimitMb(int value) {
        if (value < MIN_LIMIT_MB || value > MAX_LIMIT_MB) return false;
        prefs.edit().putInt("limit_mb", value).apply();
        scheduleTrim();
        return true;
    }

    public Usage usage() {
        return readUsage();
    }

    public Usage trimToLimit() { return clean(false); }

    /** The active song and in-flight downloads remain until their owners release them. */
    public Usage clearUnused() { return clean(true); }

    private Usage clean(boolean all) {
        synchronized (CLEANUP) {
            File[] files = directory.listFiles();
            if (files == null) return readUsage();
            // Remove abandoned temporary files and descriptor remnants, never live downloads.
            for (File file : files) {
                String name = file.getName();
                synchronized (FILES) {
                    if (name.endsWith(".part") && !inUse(file)) file.delete();
                    else if (name.endsWith(".track.audio") || name.endsWith(".track.complete")) {
                        File track = new File(directory, name.substring(0, name.lastIndexOf('.')));
                        if (!track.isFile() && !inUse(track)) file.delete();
                    }
                }
            }
            long used = readUsage().bytes;
            long missingSpace = Math.max(0L, FREE_SPACE_RESERVE - directory.getUsableSpace());
            long limit = Math.min(limitMb() * MB, Math.max(0L, used - missingSpace));
            List<Entry> candidates = new ArrayList<Entry>();
            for (File file : files) if (file.getName().endsWith(".track")) candidates.add(new Entry(file));
            Collections.sort(candidates, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) {
                    long difference = a.accessedAt - b.accessedAt;
                    return difference < 0 ? -1 : difference > 0 ? 1 : a.file.getName().compareTo(b.file.getName());
                }
            });
            for (Entry entry : candidates) {
                if (!all && used <= limit) break;
                File file = entry.file;
                synchronized (FILES) {
                    if (inUse(file)) continue;
                    long bytes = file.length();
                    if (!file.delete()) continue;
                    used -= bytes;
                    for (String suffix : new String[] {".complete", ".audio"}) {
                        File extra = new File(file.getPath() + suffix);
                        long length = extra.length();
                        if (extra.delete()) used -= length;
                    }
                }
            }
            return readUsage();
        }
    }

    private Usage readUsage() {
        long bytes = 0L;
        int songs = 0;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (!file.isFile() || !isManaged(file.getName())) continue;
            bytes += file.length();
            if (file.getName().endsWith(".track") && file.length() > 0L
                    && new File(file.getPath() + ".complete").isFile()
                    && new File(file.getPath() + ".audio").isFile()) songs++;
        }
        return new Usage(bytes, songs, directory.getUsableSpace());
    }

    private static boolean isManaged(String name) {
        return name.endsWith(".track") || name.endsWith(".track.complete")
                || name.endsWith(".track.audio") || name.endsWith(".part");
    }

    private static boolean inUse(File file) { return IN_USE.containsKey(file.getAbsolutePath()); }

    private static final class Entry {
        final File file;
        final long accessedAt;
        Entry(File file) { this.file = file; accessedAt = file.lastModified(); }
    }

    Lease protect(File file) {
        synchronized (FILES) {
            String path = file.getAbsolutePath();
            Integer count = IN_USE.get(path);
            IN_USE.put(path, count == null ? 1 : count + 1);
            return new Lease(path);
        }
    }

    File createTemporary() throws java.io.IOException {
        synchronized (FILES) {
            File file = File.createTempFile("original-", ".part", directory);
            IN_USE.put(file.getAbsolutePath(), 1);
            return file;
        }
    }

    Lease temporaryLease(File file) { return new Lease(file.getAbsolutePath()); }

    final class Lease implements Closeable {
        private final String path;
        private boolean closed;
        private Lease(String path) { this.path = path; }
        @Override public void close() {
            synchronized (FILES) {
                if (closed) return;
                closed = true;
                Integer count = IN_USE.get(path);
                if (count == null || count <= 1) IN_USE.remove(path);
                else IN_USE.put(path, count - 1);
            }
            scheduleTrim();
        }
    }

    void scheduleTrim() {
        final String path = directory.getAbsolutePath();
        synchronized (FILES) {
            if (!PENDING_TRIMS.add(path)) return;
        }
        MAINTENANCE.execute(new Runnable() {
            @Override public void run() {
                synchronized (FILES) { PENDING_TRIMS.remove(path); }
                trimToLimit();
            }
        });
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1024L * MB) return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * MB));
        if (bytes >= MB) return String.format(Locale.US, "%.1f MB", bytes / (double) MB);
        if (bytes >= 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return Math.max(0L, bytes) + " B";
    }

    public static final class Usage {
        public final long bytes, availableBytes;
        public final int songs;
        private Usage(long bytes, int songs, long availableBytes) {
            this.bytes = bytes; this.songs = songs; this.availableBytes = availableBytes;
        }
    }
}
