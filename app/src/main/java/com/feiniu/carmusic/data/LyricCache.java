package com.feiniu.carmusic.data;

import android.content.Context;
import java.io.*;
import java.util.Arrays;
import java.util.Comparator;

/** Bounded private cache of successful lyrics for one NAS, user and song per key. */
final class LyricCache {
    private static final int MAX_ENTRY = 256 * 1024;
    private static final int MAX_TOTAL = 4 * 1024 * 1024;
    private static final long FRESH_MS = 7L * 24 * 60 * 60 * 1000;

    static File entry(Context context, String key) {
        return new File(new File(context.getCacheDir(), "lyrics"), key + ".lrc");
    }

    static boolean fresh(File file) {
        long age = System.currentTimeMillis() - file.lastModified();
        return age >= 0 && age < FRESH_MS;
    }

    static synchronized String read(File file) {
        long length = file.length();
        if (length <= 0 || length > MAX_ENTRY) return "";
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            byte[] body = new byte[(int) length];
            input.readFully(body);
            return new String(body, "UTF-8");
        } catch (IOException unavailable) { return ""; }
    }

    static synchronized void save(File file, String lyric) {
        File temp = null;
        try {
            if (lyric.trim().isEmpty()) return;
            byte[] body = lyric.getBytes("UTF-8");
            if (body.length > MAX_ENTRY) return;
            File dir = file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            temp = File.createTempFile("lyrics-", ".tmp", dir);
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(body); }
            if (!temp.renameTo(file)) return;
            File[] entries = dir.listFiles();
            if (entries == null) return;
            long bytes = 0;
            for (File entry : entries) bytes += entry.length();
            if (bytes <= MAX_TOTAL) return;
            Arrays.sort(entries, new Comparator<File>() {
                public int compare(File a, File b) {
                    long first = a.lastModified(), second = b.lastModified();
                    return first < second ? -1 : first == second ? 0 : 1;
                }
            });
            for (File entry : entries) {
                if (bytes <= MAX_TOTAL) break;
                long size = entry.length();
                if (!entry.equals(file) && entry.delete()) bytes -= size;
            }
        } catch (IOException ignored) {
            // A full cache volume must not prevent displaying freshly downloaded lyrics.
        } finally { if (temp != null) temp.delete(); }
    }
}
