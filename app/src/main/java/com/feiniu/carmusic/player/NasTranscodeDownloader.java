package com.feiniu.carmusic.player;

import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.data.ApiException;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NasTranscodeDownloader {
    interface ProgressListener { void onProgress(AudioFile audio, long bytes, boolean complete) throws Exception; }
    interface StageListener { void onStage(String stage); }
    private static final KeyedLocks SESSION_LOCKS = new KeyedLocks();
    private final FnApiClient api;
    private final long readinessWaitMs;
    NasTranscodeDownloader(FnApiClient api) { this(api, 20000L); }
    NasTranscodeDownloader(FnApiClient api, long readinessWaitMs) {
        this.api = api;
        this.readinessWaitMs = Math.max(1L, readinessWaitMs);
    }

    AudioFile download(final String guid, String codec, File output) throws Exception {
        return download(guid, codec, 192, output, null);
    }

    AudioFile download(final String guid, String codec, int bitrateKbps, File output, ProgressListener progress) throws Exception {
        return download(guid, codec, bitrateKbps, output, progress, null);
    }

    AudioFile download(final String guid, String codec, int bitrateKbps, File output,
                       ProgressListener progress, StageListener stages) throws Exception {
        final long startedAt = System.nanoTime();
        String key = api.session().getBaseUrl() + "|" + guid;
        KeyedLocks.Guard lock = SESSION_LOCKS.acquire(key);
        ScheduledExecutorService heartbeat = null;
        ScheduledFuture<?> heartbeatTask = null;
        boolean requested = false;
        final AtomicBoolean ended = new AtomicBoolean();
        final AtomicLong downloadedMs = new AtomicLong();
        final AtomicLong lastHeartbeatMs = new AtomicLong();
        try {
            stage(stages, "取得转码会话", startedAt);
            checkInterrupted();
            requested = true;
            api.startTranscode(guid, codec, bitrateKbps);
            stage(stages, "NAS 转码启动响应", startedAt);
            final long startupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readinessWaitMs);
            heartbeat = Executors.newSingleThreadScheduledExecutor();
            heartbeatTask = heartbeat.scheduleAtFixedRate(new Runnable() {
                @Override public void run() {
                    if (ended.get()) return;
                    long timestamp = Math.max(downloadedMs.get(), lastHeartbeatMs.get() + 1L);
                    lastHeartbeatMs.set(timestamp);
                    try { api.transcodeHeartbeat(guid, timestamp / 1000.0); }
                    catch (Exception ignored) { /* Resource reads report a genuinely expired session. */ }
                }
            }, 10L, 10L, TimeUnit.SECONDS);

            HlsAudioAssembler assembler = new HlsAudioAssembler(output, codec);
            try {
                String playlistUrl = api.transcodePlaylistUrl(guid);
                String currentMap = "";
                long nextSequence = 0L;
                int downloadedSegments = 0;
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
                while (System.nanoTime() < deadline) {
                    checkInterrupted();
                    long resourceDeadline = downloadedSegments == 0 ? startupDeadline
                            : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readinessWaitMs);
                    Playlist playlist = readPlaylist(playlistUrl, 0, resourceDeadline);
                    if (downloadedSegments == 0 && !playlist.segments.isEmpty())
                        stage(stages, "转码列表就绪", startedAt);
                    for (Segment segment : playlist.segments) {
                        if (segment.sequence < nextSequence) continue;
                        if (segment.sequence != nextSequence) throw new ApiException("转码分片缺失，无法生成完整音频");
                        if (downloadedSegments >= 5000) throw new ApiException("转码分片数量超出限制");
                        if (!segment.mapUrl.equals(currentMap)) {
                            if (currentMap.length() > 0) throw new ApiException("转码中途改变初始化段，无法安全合并");
                            if (segment.mapUrl.length() > 0) {
                                assembler.acceptInitialization(readWithRetry(segment.mapUrl, 2 * 1024 * 1024,
                                        "转码初始化段", resourceDeadline));
                                currentMap = segment.mapUrl;
                                stage(stages, "转码初始化段就绪", startedAt);
                            }
                        }
                        if (downloadedSegments > 0) resourceDeadline = System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(readinessWaitMs);
                        assembler.appendSegment(readWithRetry(segment.url, 8 * 1024 * 1024,
                                "转码音频分片 " + segment.sequence, resourceDeadline));
                        if (downloadedSegments == 0) stage(stages, "首段音频就绪", startedAt);
                        if (progress != null && assembler.getTotalSamples() > 0)
                            progress.onProgress(audioFile(output, assembler), output.length(), false);
                        if (assembler.getWrittenBytes() > 512L * 1024L * 1024L) throw new ApiException("转码歌曲超出缓存容量");
                        downloadedMs.addAndGet(Math.round(segment.duration * 1000.0));
                        nextSequence++;
                        downloadedSegments++;
                    }
                    if (playlist.complete) {
                        if (downloadedSegments == 0) throw new ApiException("NAS 未返回音频分片");
                        assembler.finish();
                        AudioFile audio = audioFile(output, assembler);
                        stage(stages, "完整音频就绪", startedAt);
                        if (progress != null) progress.onProgress(audio, output.length(), true);
                        return audio;
                    }
                    if (downloadedSegments == 0 && System.nanoTime() >= startupDeadline)
                        throw new ApiException("转码播放列表持续未提供音频分片，请检查 NAS 转码状态后重试");
                    Thread.sleep(downloadedSegments == 0 ? Math.max(1L, Math.min(1000L,
                            TimeUnit.NANOSECONDS.toMillis(startupDeadline - System.nanoTime()))) : 1000L);
                }
                throw new ApiException("NAS 转码下载超时，请重试或切换原始音质");
            } finally { assembler.close(); }
        } finally {
            ended.set(true);
            if (heartbeatTask != null) heartbeatTask.cancel(true);
            if (heartbeat != null) {
                heartbeat.shutdownNow();
                // Serialize old-session cleanup before a new request for the same song.
                boolean interrupted = Thread.interrupted();
                try { heartbeat.awaitTermination(10, TimeUnit.SECONDS); }
                catch (InterruptedException error) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (requested) {
                boolean interrupted = Thread.interrupted();
                try { api.quitTranscode(guid); } catch (Exception ignored) { }
                finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            lock.close();
        }
    }

    private static AudioFile audioFile(File output, HlsAudioAssembler assembler) {
        return new AudioFile(output, assembler.getCodec(), assembler.getSampleRate(),
                assembler.getBitDepth(), assembler.getChannels(), true, assembler.getBitrateKbps());
    }

    private static void stage(StageListener listener, String name, long startedAt) {
        if (listener != null) listener.onStage(name + " · "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) + " ms");
    }

    private Playlist readPlaylist(String url, int depth, long deadline) throws Exception {
        if (depth > 3) throw new ApiException("转码播放列表嵌套过深");
        Playlist playlist = parsePlaylist(url, new String(readWithRetry(url, 1024 * 1024,
                "转码播放列表", deadline), "UTF-8"));
        return playlist.variant.length() == 0 ? playlist : readPlaylist(playlist.variant, depth + 1, deadline);
    }

    private byte[] readWithRetry(String url, int limit, String resource, long deadline) throws Exception {
        Exception last = null;
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            checkInterrupted();
            long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            try { return api.readMusicResource(url, limit, (int) Math.min(4000L, remainingMs), remainingMs); }
            catch (Exception error) {
                checkInterrupted();
                // A socket timeout is recoverable; an actual cancellation must stop immediately.
                if (error instanceof InterruptedIOException && !(error instanceof SocketTimeoutException)) throw error;
                if (!retryable(error)) throw resourceFailure(resource, error, false);
                last = error;
                long delay = Math.min(1000L, 250L * ++attempt);
                remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs > 0L) Thread.sleep(Math.min(delay, remainingMs));
            }
        }
        checkInterrupted();
        throw resourceFailure(resource, last, true);
    }

    private static boolean retryable(Exception error) {
        if (error instanceof ApiException) {
            int status = ((ApiException) error).getStatusCode();
            return status == 404 || status == 408 || status == 429 || status >= 500 && status <= 599;
        }
        return error instanceof IOException;
    }

    private static ApiException resourceFailure(String resource, Exception cause, boolean expired) {
        int status = cause instanceof ApiException ? ((ApiException) cause).getStatusCode() : 0;
        String detail = cause == null ? "等待资源超时" : cause.getMessage();
        if (expired && status == 404) detail = "持续返回 HTTP 404，资源尚未生成、会话已失效或访问路径不可用，请重试并检查 NAS 转码状态与转发配置";
        ApiException error = new ApiException(resource + (expired ? "等待超时：" : "读取失败：") + detail, status);
        if (cause != null) error.initCause(cause);
        return error;
    }

    static Playlist parsePlaylist(String url, String text) throws Exception {
        text = text.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (!text.startsWith("#EXTM3U")) throw new ApiException("NAS 未返回有效的转码播放列表");
        Playlist result = new Playlist();
        String map = "";
        long sequence = 0L;
        double duration = 0.0;
        boolean variantNext = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) sequence = Long.parseLong(line.substring(22));
            else if (line.startsWith("#EXT-X-MAP:")) {
                if (line.contains("BYTERANGE=")) throw new ApiException("暂不支持字节范围转码初始化段");
                map = new URL(new URL(url), attribute(line.substring(11), "URI")).toString();
            } else if (line.startsWith("#EXTINF:")) {
                duration = Double.parseDouble(line.substring(8).split(",", 2)[0]);
                if (Double.isNaN(duration) || Double.isInfinite(duration) || duration < 0.0) throw new ApiException("转码分片时长无效");
            } else if (line.startsWith("#EXT-X-KEY:") && !"NONE".equals(attribute(line.substring(11), "METHOD"))) {
                throw new ApiException("暂不支持加密的转码音频");
            } else if (line.startsWith("#EXT-X-BYTERANGE") || line.startsWith("#EXT-X-DISCONTINUITY")) {
                throw new ApiException("暂不支持此转码分片布局");
            } else if (line.equals("#EXT-X-ENDLIST")) result.complete = true;
            else if (line.startsWith("#EXT-X-STREAM-INF:")) variantNext = true;
            else if (line.length() > 0 && !line.startsWith("#")) {
                String resolved = new URL(new URL(url), line).toString();
                if (variantNext) { result.variant = resolved; return result; }
                result.segments.add(new Segment(resolved, map, sequence++, duration));
            }
        }
        return result;
    }

    private static String attribute(String line, String key) throws ApiException {
        Matcher matcher = Pattern.compile("(?:^|,)" + Pattern.quote(key) + "=(?:\"([^\"]*)\"|([^,]*))").matcher(line);
        if (!matcher.find()) throw new ApiException("转码列表缺少 " + key);
        return matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
    }

    static final class Playlist {
        final List<Segment> segments = new ArrayList<Segment>();
        boolean complete;
        String variant = "";
    }
    static final class Segment {
        final String url, mapUrl;
        final long sequence;
        final double duration;
        Segment(String url, String map, long sequence, double duration) {
            this.url = url; this.mapUrl = map; this.sequence = sequence; this.duration = duration;
        }
    }
}
