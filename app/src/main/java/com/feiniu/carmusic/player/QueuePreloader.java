package com.feiniu.carmusic.player;

import android.content.Context;
import com.feiniu.carmusic.diagnostics.AppLog;
import com.feiniu.carmusic.model.Track;
import java.util.*;
import java.util.concurrent.*;

/** Serial, bounded lookahead; selecting an in-flight song waits on the same download. */
final class QueuePreloader {
    static final class Request {
        final Track track;
        final String codec, key;
        final int bitrate;
        Request(Track track, String codec, int bitrate, TrackFileCache cache) {
            this.track = track; this.codec = codec; this.bitrate = bitrate;
            key = cache.requestKey(track, codec, bitrate);
        }
    }
    private final TrackFileCache cache;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Job> jobs = new LinkedHashMap<String, Job>();
    private boolean closed;

    QueuePreloader(Context context, TrackFileCache cache) { this.cache = cache; this.context = context.getApplicationContext(); }

    synchronized void plan(List<Request> requests, String currentKey) {
        if (closed) return;
        Set<String> keep = new HashSet<String>();
        if (currentKey != null) keep.add(currentKey);
        for (Request request : requests) keep.add(request.key);
        discardExcept(keep);
        for (Request request : requests) {
            if (jobs.containsKey(request.key)) continue;
            Job job = new Job(request);
            jobs.put(request.key, job);
            worker.execute(job.task);
        }
    }

    synchronized void retainCurrent(String key) {
        discardExcept(Collections.singleton(key));
    }

    AudioFile await(Track track, String codec, int bitrate) throws Exception {
        Job job;
        synchronized (this) { job = jobs.get(cache.requestKey(track, codec, bitrate)); }
        if (job == null) return null;
        try {
            AudioFile result = job.task.get();
            return result != null && result.file.isFile() && new java.io.File(result.file.getPath() + ".complete").isFile()
                    ? result : null;
        } catch (ExecutionException | CancellationException failed) { return null; }
    }

    synchronized void clear() { discardExcept(Collections.<String>emptySet()); }
    synchronized void close() { closed = true; clear(); worker.shutdownNow(); }

    private void discardExcept(Set<String> keep) {
        Iterator<Map.Entry<String, Job>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Job> entry = iterator.next();
            if (keep.contains(entry.getKey())) continue;
            entry.getValue().task.cancel(true);
            entry.getValue().lease.close();
            iterator.remove();
        }
    }

    private final class Job {
        final SongCache.Lease lease;
        final FutureTask<AudioFile> task;
        Job(final Request request) {
            lease = cache.protectTrack(request.track, request.codec, request.bitrate);
            task = new FutureTask<AudioFile>(new Callable<AudioFile>() {
                @Override public AudioFile call() throws Exception {
                    SongCache storage = new SongCache(context);
                    try {
                        storage.trimToLimit();
                        if (storage.usage().bytes > storage.limitMb() * 1024L * 1024L) { lease.close(); return null; }
                        AudioFile result = cache.getOrDownload(request.track, request.codec, request.bitrate, null);
                        storage.trimToLimit();
                        if (storage.usage().bytes > storage.limitMb() * 1024L * 1024L) lease.close();
                        return result;
                    } catch (Exception error) {
                        lease.close();
                        if (!Thread.currentThread().isInterrupted()) AppLog.warn("playback.preload_failed", error.getClass().getSimpleName());
                        throw error;
                    }
                }
            });
        }
    }
}
