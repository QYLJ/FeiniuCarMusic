package com.feiniu.carmusic.player;

import android.content.Context;

import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.locks.ReentrantLock;

final class TrackFileCache {
    interface ReadyListener { void onReady(ProgressiveAudioFile audio) throws Exception; }
    private static final long MAX_CACHE_BYTES = 512L * 1024L * 1024L;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[16];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new ReentrantLock(); }

    private final File directory;
    private final FnApiClient api;

    TrackFileCache(Context context) {
        directory = new File(context.getCacheDir(), "tracks");
        if (!directory.exists()) directory.mkdirs();
        api = FnApiClient.get(context);
    }

    AudioFile getOrDownload(Track track, String transcodeCodec) throws Exception {
        return getOrDownload(track, transcodeCodec, 192, null);
    }

    AudioFile getOrDownload(Track track, String transcodeCodec, int bitrateKbps, ReadyListener ready) throws Exception {
        String key = api.session().getBaseUrl() + "|" + api.session().getUsername()
                + "|" + track.guid + "|" + transcodeCodec
                + ("mp3".equals(transcodeCodec) ? "|" + bitrateKbps : "") + "|stream-audio-v3";
        ReentrantLock lock = LOCKS[(key.hashCode() & 0x7fffffff) % LOCKS.length];
        lock.lockInterruptibly();
        try { return downloadLocked(track, transcodeCodec, bitrateKbps, key, ready); }
        finally { lock.unlock(); }
    }

    private AudioFile downloadLocked(final Track track, String transcodeCodec, int bitrateKbps,
                                     String key, final ReadyListener ready) throws Exception {
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
                target.setLastModified(System.currentTimeMillis());
                if (ready != null) {
                    ProgressiveAudioFile live = new ProgressiveAudioFile(cached);
                    live.finish();
                    try { ready.onReady(live); }
                    catch (Exception error) { live.close(); throw error; }
                }
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
                api.downloadTrack(track.guid, target, ready == null ? null : new FnApiClient.TrackBufferListener() {
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
                audio = new NasTranscodeDownloader(api).download(track.guid, transcodeCodec, bitrateKbps, target,
                        ready == null ? null : new NasTranscodeDownloader.ProgressListener() {
                            @Override public void onProgress(AudioFile audio, long bytes, boolean finished) throws Exception {
                                if (live[0] == null) {
                                    live[0] = new ProgressiveAudioFile(audio);
                                    live[0].publish(bytes, -1);
                                    if (finished) live[0].finish();
                                    ready.onReady(live[0]);
                                } else {
                                    live[0].publish(bytes, -1);
                                    if (finished) live[0].finish();
                                }
                            }
                        });
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("下载已取消");
            if (target.length() <= 0L) throw new Exception("音频文件为空");
            writeDescription(audio, description);
            if (!completeMarker.createNewFile() && !completeMarker.isFile()) {
                throw new Exception("无法标记歌曲缓存");
            }
            completed = true;
            if (ready != null && live[0] == null) {
                live[0] = new ProgressiveAudioFile(audio);
                live[0].finish();
                ready.onReady(live[0]);
            } else if (live[0] != null) live[0].finish();
            target.setLastModified(System.currentTimeMillis());
            cleanup(target);
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

    private void cleanup(File protectedFile) {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                long difference = left.lastModified() - right.lastModified();
                return difference < 0 ? -1 : difference > 0 ? 1 : 0;
            }
        });
        long total = 0L;
        for (File file : files) if (file.getName().endsWith(".track")) total += file.length();
        for (File file : files) {
            if (total <= MAX_CACHE_BYTES) break;
            if (file.equals(protectedFile) || !file.getName().endsWith(".track")) continue;
            long length = file.length();
            if (file.delete()) {
                new File(directory, file.getName() + ".complete").delete();
                new File(directory, file.getName() + ".audio").delete();
                total -= length;
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
