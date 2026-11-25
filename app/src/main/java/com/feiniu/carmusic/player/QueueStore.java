package com.feiniu.carmusic.player;

import android.content.Context;
import android.content.SharedPreferences;

import com.feiniu.carmusic.model.Track;

import android.util.JsonReader;
import com.feiniu.carmusic.diagnostics.AppLog;
import org.json.JSONObject;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;

final class QueueStore {
    private static final String PREFS = "playback_queue";
    // Shared across service recreation so an old writer cannot overwrite a newer queue.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "PlaybackQueueWriter");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final SharedPreferences prefs;
    private final File file;
    private final File backup;
    private final ExecutorService writer = WRITER;
    private List<Track> pending;
    private boolean writing;
    private boolean closed;

    QueueStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        file = new File(context.getFilesDir(), "playback-queue.jsonl");
        backup = new File(file.getPath() + ".bak");
    }

    synchronized void saveQueue(List<Track> tracks) {
        if (closed) return;
        // Keep only the latest pending page append, rather than retaining many full snapshots.
        pending = new ArrayList<Track>(tracks);
        if (writing) return;
        writing = true;
        writer.execute(new Runnable() {
            @Override public void run() {
                while (true) {
                    List<Track> snapshot;
                    synchronized (QueueStore.this) {
                        snapshot = pending;
                        pending = null;
                        if (snapshot == null) { writing = false; return; }
                    }
                    try { writeQueue(snapshot); }
                    catch (Exception error) { AppLog.error("queue.save_failed", error); }
                }
            }
        });
    }

    void savePosition(int index, int positionMs) {
        prefs.edit().putInt("index", index).putInt("position", positionMs).apply();
    }

    private void writeQueue(List<Track> tracks) throws Exception {
        File temporary = new File(file.getPath() + ".tmp");
        FileOutputStream output = new FileOutputStream(temporary);
        try {
            BufferedWriter text = new BufferedWriter(new OutputStreamWriter(output, "UTF-8"), 8192);
            for (Track track : tracks) {
                text.write(track.toJson().toString());
                text.newLine();
            }
            text.flush();
            output.getFD().sync();
        } finally { output.close(); }
        if (file.exists()) {
            if (backup.exists() && !backup.delete()) throw new IOException("无法清理队列备份");
            if (!file.renameTo(backup)) throw new IOException("无法备份播放队列");
        }
        if (!temporary.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file);
            throw new IOException("无法保存播放队列");
        }
        backup.delete();
        prefs.edit().remove("tracks").apply();
    }

    RestoredQueue restore() {
        try {
            return writer.submit(new Callable<RestoredQueue>() {
                @Override public RestoredQueue call() { return readQueue(); }
            }).get();
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            AppLog.error("queue.restore_failed", error);
            return new RestoredQueue();
        }
    }

    private RestoredQueue readQueue() {
        RestoredQueue result = new RestoredQueue();
        try {
            File source = file.isFile() ? file : backup;
            if (source.isFile()) {
                BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(source), "UTF-8"));
                try {
                    String line;
                    while ((line = input.readLine()) != null) {
                        if (line.length() > 0) result.tracks.add(Track.fromStoredJson(new JSONObject(line)));
                    }
                } finally { input.close(); }
            } else if (prefs.contains("tracks")) {
                // Migrate old installations without allocating the complete JSONArray/object tree.
                JsonReader input = new JsonReader(new StringReader(prefs.getString("tracks", "[]")));
                try {
                    input.beginArray();
                    while (input.hasNext()) result.tracks.add(readLegacyTrack(input));
                    input.endArray();
                } finally { input.close(); }
                saveQueue(result.tracks);
            }
            result.index = prefs.getInt("index", result.tracks.isEmpty() ? -1 : 0);
            if (result.index < 0 || result.index >= result.tracks.size()) result.index = result.tracks.isEmpty() ? -1 : 0;
            result.positionMs = Math.max(0, prefs.getInt("position", 0));
        } catch (Exception error) {
            AppLog.error("queue.restore_failed", error);
            result.tracks.clear();
            result.index = -1;
            result.positionMs = 0;
        }
        return result;
    }

    void clear() {
        savePosition(-1, 0);
        saveQueue(new ArrayList<Track>());
    }

    synchronized void close() {
        closed = true;
        // The application writer finishes pending work without blocking service destruction.
    }

    private static Track readLegacyTrack(JsonReader input) throws IOException {
        Track track = new Track();
        input.beginObject();
        while (input.hasNext()) {
            String key = input.nextName();
            if ("guid".equals(key)) track.guid = input.nextString();
            else if ("title".equals(key)) track.title = input.nextString();
            else if ("artist".equals(key)) track.artist = input.nextString();
            else if ("album".equals(key)) track.album = input.nextString();
            else if ("coverId".equals(key)) track.coverId = input.nextString();
            else if ("durationMs".equals(key)) track.durationMs = input.nextLong();
            else if ("accessStatus".equals(key)) track.accessStatus = input.nextInt();
            else if ("favorite".equals(key)) track.favorite = input.nextBoolean();
            else if ("codec".equals(key)) track.codec = input.nextString();
            else if ("format".equals(key)) track.format = input.nextString();
            else if ("channels".equals(key)) track.channels = input.nextInt();
            else if ("bitDepth".equals(key)) track.bitDepth = input.nextInt();
            else if ("sampleRate".equals(key)) track.sampleRate = input.nextInt();
            else if ("bitrate".equals(key)) track.bitrate = input.nextInt();
            else input.skipValue();
        }
        input.endObject();
        return track;
    }

    static final class RestoredQueue {
        final List<Track> tracks = new ArrayList<Track>();
        int index = -1;
        int positionMs;
    }
}
