package com.feiniu.carmusic.player;

import android.content.Context;

import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.data.FnConnectResolver;
import com.feiniu.carmusic.model.Track;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;

final class TrackFileCache {
    interface ReadyListener { void onReady(ProgressiveAudioFile audio) throws Exception; }
    interface CompleteListener { void onComplete(AudioFile audio) throws Exception; }
    private static final KeyedLocks LOCKS = new KeyedLocks();

    private final File directory;
    private final FnApiClient api;
    private final SongCache storage;
    private final Context context;
    private final boolean snapshot;

    TrackFileCache(Context context) {
        this(context, CacheDirectories.resolve(context), false);
    }

    private TrackFileCache(Context context, File directory, boolean snapshot) {
        this.context = context.getApplicationContext();
        this.directory = directory;
        this.snapshot = snapshot;
        api = FnApiClient.get(context);
        storage = new SongCache(context, directory);
        storage.scheduleTrim();
    }

    AudioFile getOrDownload(Track track, String transcodeCodec) throws Exception {
        return getOrDownload(track, transcodeCodec, 192, null);
    }

    AudioFile getOrDownload(Track track, String transcodeCodec, int bitrateKbps, ReadyListener ready) throws Exception {
        return getOrDownload(track, transcodeCodec, bitrateKbps, ready, null);
    }

    AudioFile getOrDownload(Track track, String transcodeCodec, int bitrateKbps, ReadyListener ready,
                           NasTranscodeDownloader.StageListener stages) throws Exception {
        return getOrDownload(track, transcodeCodec, bitrateKbps, ready, stages, null);
    }

    AudioFile getOrDownload(Track track, String transcodeCodec, int bitrateKbps, ReadyListener ready,
                           NasTranscodeDownloader.StageListener stages, CompleteListener complete) throws Exception {
        if (!snapshot) return current().getOrDownload(track, transcodeCodec, bitrateKbps, ready, stages, complete);
        FnApiClient requestApi = api.snapshot();
        String key = cacheKey(track, transcodeCodec, bitrateKbps, requestApi);
        long started = System.nanoTime();
        KeyedLocks.Guard lock = LOCKS.acquire(key);
        SongCache.Lease retained = storage.protect(targetFor(key));
        try {
            migrateLegacy(track, transcodeCodec, bitrateKbps, targetFor(key), requestApi);
            if (stages != null) stages.onStage("取得歌曲缓存 · "
                    + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + " ms");
            return downloadLocked(track, transcodeCodec, bitrateKbps, key, ready, stages, complete, requestApi);
        } finally { retained.close(); lock.close(); }
    }

    private String cacheKey(Track track, String transcodeCodec, int bitrateKbps) {
        return cacheKey(track, transcodeCodec, bitrateKbps, api);
    }

    private String cacheKey(Track track, String transcodeCodec, int bitrateKbps, FnApiClient source) {
        String identity = source.session().getBaseUrl();
        String login = source.session().getLoginAddress();
        if (FnConnectResolver.looksLikeFnIdInput(login)) {
            try { identity = "fnid:" + FnConnectResolver.normalizeFnId(login); }
            catch (Exception ignored) { }
        }
        return identity + "|" + source.session().getUsername()
                + "|" + track.guid + "|" + transcodeCodec
                + ("mp3".equals(transcodeCodec) ? "|" + bitrateKbps : "") + "|stream-audio-v3";
    }

    AudioFile cachedOriginal(Track track) {
        if (!snapshot) return current().cachedOriginal(track);
        SongCache.Lease retained = null;
        KeyedLocks.Guard lock = null;
        try {
            FnApiClient source = api.snapshot();
            String key = cacheKey(track, "", 192, source);
            lock = LOCKS.acquire(key);
            File target = targetFor(key);
            retained = storage.protect(target);
            migrateLegacy(track, "", 192, target, source);
            if (target.length() <= 0 || !new File(target.getPath() + ".complete").isFile()) return null;
            AudioFile audio = readDescription(target, new File(target.getPath() + ".audio"));
            target.setLastModified(System.currentTimeMillis());
            return audio;
        } catch (Exception ignored) { return null; }
        finally {
            if (retained != null) retained.close();
            if (lock != null) lock.close();
        }
    }

    SongCache.Lease protectTrack(Track track, String codec, int bitrate) {
        if (!snapshot) return current().protectTrack(track, codec, bitrate);
        return storage.protect(targetFor(cacheKey(track, codec, bitrate)));
    }

    String requestKey(Track track, String codec, int bitrate) {
        if (!snapshot) return current().requestKey(track, codec, bitrate);
        return directory.getAbsolutePath() + "|" + cacheKey(track, codec, bitrate);
    }

    private TrackFileCache current() { return new TrackFileCache(context, CacheDirectories.resolve(context), true); }

    private File targetFor(String key) {
        try { return new File(directory, hash(key) + ".track"); }
        catch (Exception error) { throw new IllegalStateException("无法生成缓存文件名", error); }
    }

    private void migrateLegacy(Track track, String codec, int bitrate, File target, FnApiClient source) throws Exception {
        if (target.exists()) return;
        String key = source.session().getBaseUrl() + "|" + source.session().getUsername() + "|" + track.guid
                + "|" + codec + ("mp3".equals(codec) ? "|" + bitrate : "") + "|stream-audio-v3";
        File legacy = targetFor(key);
        if (legacy.equals(target)) return;
        SongCache.Lease retained = storage.protect(legacy);
        try {
            File marker = new File(legacy.getPath() + ".complete");
            File description = new File(legacy.getPath() + ".audio");
            if (!legacy.isFile() || !marker.isFile() || !description.isFile()) return;
            try { readDescription(legacy, description); }
            catch (Exception damaged) { return; }
            if (!legacy.renameTo(target)) return;
            File nextDescription = new File(target.getPath() + ".audio");
            if (!description.renameTo(nextDescription)) { target.renameTo(legacy); return; }
            if (!marker.renameTo(new File(target.getPath() + ".complete"))) {
                nextDescription.renameTo(description);
                target.renameTo(legacy);
            }
        } finally { retained.close(); }
    }

    StreamCache cacheOriginalStream(Track track) throws Exception { return cacheOriginalStream(track, api.snapshot()); }
    StreamCache cacheOriginalStream(Track track, FnApiClient source) throws Exception {
        if (!snapshot) return current().cacheOriginalStream(track, source);
        return new StreamCache(track, source);
    }

    /** Only a fully consumed response is promoted to the regular offline cache. */
    final class StreamCache implements java.io.Closeable {
        private final Track track;
        private final String key;
        private final File temporary;
        private final FileOutputStream output;
        private final SongCache.Lease retainedTemporary;
        private boolean outputClosed;
        StreamCache(Track track, FnApiClient source) throws Exception {
            this.track = track;
            key = cacheKey(track, "", 192, source);
            temporary = storage.createTemporary();
            retainedTemporary = storage.temporaryLease(temporary);
            try { output = new FileOutputStream(temporary); }
            catch (Exception error) { retainedTemporary.close(); temporary.delete(); throw error; }
        }
        void write(byte[] bytes, int count) throws Exception { output.write(bytes, 0, count); }
        void finish(long expectedLength) throws Exception {
            output.close(); outputClosed = true;
            if (temporary.length() <= 0 || (expectedLength >= 0 && temporary.length() != expectedLength)) return;
            KeyedLocks.Guard lock = LOCKS.acquire(key);
            SongCache.Lease retained = storage.protect(targetFor(key));
            try {
                File target = targetFor(key);
                File marker = new File(target.getPath() + ".complete");
                if (marker.isFile()) return;
                if (target.exists() && !target.delete()) return;
                if (!temporary.renameTo(target)) return;
                AudioFile audio = new AudioFile(target, track.codec, track.sampleRate, track.bitDepth,
                        track.channels, false, track.bitrate > 1000 ? track.bitrate / 1000 : track.bitrate);
                writeDescription(audio, new File(target.getPath() + ".audio"));
                if (!marker.createNewFile() && !marker.isFile()) throw new java.io.IOException("无法标记歌曲缓存");
                storage.scheduleTrim();
            } finally { retained.close(); lock.close(); }
        }
        @Override public void close() throws java.io.IOException {
            try { if (!outputClosed) output.close(); }
            finally { temporary.delete(); retainedTemporary.close(); }
        }
    }

    private AudioFile downloadLocked(final Track track, String transcodeCodec, int bitrateKbps,
                                     String key, final ReadyListener ready, NasTranscodeDownloader.StageListener stages,
                                     final CompleteListener complete, FnApiClient requestApi) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("下载已取消");
        File target = new File(directory, hash(key) + ".track");
        File completeMarker = new File(directory, target.getName() + ".complete");
        File description = new File(directory, target.getName() + ".audio");
        if (target.isFile() && target.length() > 0L && completeMarker.isFile() && description.isFile()) {
            AudioFile cached = null;
            try {
                cached = readDescription(target, description);
            } catch (Exception ignored) { /* Rebuild a corrupted cache descriptor. */ }
            if (cached != null) {
                if (stages != null) stages.onStage("命中完整音频缓存");
                target.setLastModified(System.currentTimeMillis());
                if (ready != null) {
                    ProgressiveAudioFile live = new ProgressiveAudioFile(cached);
                    live.finish();
                    try { ready.onReady(live); }
                    catch (Exception error) { live.close(); throw error; }
                }
                if (complete != null) complete.onComplete(cached);
                return cached;
            }
        }

        if (target.exists()) target.delete();
        if (completeMarker.exists()) completeMarker.delete();
        if (description.exists()) description.delete();
        boolean completed = false;
        final ProgressiveAudioFile[] live = {null};
        try {
            AudioFile audio;
            if (transcodeCodec.length() == 0) {
                final AudioFile original = new AudioFile(target, track.codec, track.sampleRate, track.bitDepth,
                        track.channels, false, track.bitrate > 1000 ? track.bitrate / 1000 : track.bitrate);
                requestApi.downloadTrack(track.guid, target, ready == null ? null : new FnApiClient.TrackBufferListener() {
                    @Override public void onBufferReady(long downloaded, long total) throws Exception {
                        if (live[0] == null && downloaded >= 64 * 1024L) {
                            live[0] = new ProgressiveAudioFile(original);
                            live[0].publish(downloaded, total);
                            ready.onReady(live[0]);
                        } else if (live[0] != null) live[0].publish(downloaded, total);
                    }
                });
                audio = original;
            } else {
                audio = new NasTranscodeDownloader(requestApi).download(track.guid, transcodeCodec, bitrateKbps, target,
                        ready == null && complete == null ? null : new NasTranscodeDownloader.ProgressListener() {
                            @Override public void onProgress(AudioFile audio, long bytes, boolean finished) throws Exception {
                                if (ready != null && live[0] == null) {
                                    live[0] = new ProgressiveAudioFile(audio);
                                    live[0].publish(bytes, -1);
                                    if (finished) live[0].finish();
                                    ready.onReady(live[0]);
                                } else if (live[0] != null) {
                                    live[0].publish(bytes, -1);
                                    if (finished) live[0].finish();
                                }
                                // Completed bytes are playable before NAS heartbeat/quit cleanup returns.
                                if (finished && complete != null) complete.onComplete(audio);
                            }
                        }, stages);
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("下载已取消");
            if (target.length() <= 0L) throw new Exception("音频文件为空");
            writeDescription(audio, description);
            if (!completeMarker.createNewFile() && !completeMarker.isFile()) {
                throw new Exception("无法标记歌曲缓存");
            }
            completed = true;
            if (transcodeCodec.length() == 0 && complete != null) complete.onComplete(audio);
            if (ready != null && live[0] == null) {
                live[0] = new ProgressiveAudioFile(audio);
                live[0].finish();
                ready.onReady(live[0]);
            } else if (live[0] != null) live[0].finish();
            target.setLastModified(System.currentTimeMillis());
            storage.scheduleTrim();
            return audio;
        } catch (Exception error) {
            if (live[0] != null) {
                live[0].fail(error);
                try { live[0].close(); } catch (Exception ignored) { }
            }
            throw error;
        } finally {
            if (!completed) {
                if (completeMarker.exists()) completeMarker.delete();
                if (target.exists()) target.delete();
                if (description.exists()) description.delete();
            }
        }
    }

    private static void writeDescription(AudioFile audio, File description) throws Exception {
        JSONObject json = new JSONObject().put("codec", audio.codec).put("sampleRate", audio.sampleRate)
                .put("bitDepth", audio.bitDepth).put("channels", audio.channels).put("transcoded", audio.transcoded)
                .put("bitrateKbps", audio.bitrateKbps);
        FileOutputStream output = new FileOutputStream(description);
        try { output.write(json.toString().getBytes("UTF-8")); }
        finally { output.close(); }
    }

    private static AudioFile readDescription(File target, File description) throws Exception {
        if (description.length() > 4096L) throw new Exception("缓存描述损坏");
        FileInputStream input = new FileInputStream(description);
        try {
            byte[] data = new byte[(int) description.length()];
            int offset = 0, count;
            while (offset < data.length && (count = input.read(data, offset, data.length - offset)) > 0) offset += count;
            JSONObject json = new JSONObject(new String(data, "UTF-8"));
            return new AudioFile(target, json.getString("codec"), json.getInt("sampleRate"),
                    json.getInt("bitDepth"), json.getInt("channels"), json.getBoolean("transcoded"), json.optInt("bitrateKbps", 0));
        } finally { input.close(); }
    }

    private static String hash(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(value.getBytes("UTF-8"));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }
}
