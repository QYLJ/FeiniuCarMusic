package com.feiniu.carmusic.player;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.HistoryStore;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.data.PlaybackPreferences;
import com.feiniu.carmusic.diagnostics.AppLog;
import com.feiniu.carmusic.model.Track;
import com.feiniu.carmusic.ui.PlayerActivity;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlaybackService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_PLAY = "com.feiniu.carmusic.PLAY";
    public static final String ACTION_PAUSE = "com.feiniu.carmusic.PAUSE";
    public static final String ACTION_TOGGLE = "com.feiniu.carmusic.TOGGLE";
    public static final String ACTION_NEXT = "com.feiniu.carmusic.NEXT";
    public static final String ACTION_PREVIOUS = "com.feiniu.carmusic.PREVIOUS";
    public static final String ACTION_STOP = "com.feiniu.carmusic.STOP";

    public static final int MODE_SEQUENCE = 0;
    public static final int MODE_REPEAT_ALL = 1;
    public static final int MODE_REPEAT_ONE = 2;
    public static final int MODE_SHUFFLE = 3;

    private static final int NOTIFICATION_ID = 41;
    private static final String TAG = "PlaybackService";
    private static final String NOTIFICATION_CHANNEL = "playback";

    private final LocalBinder binder = new LocalBinder();
    private final List<Track> queue = new ArrayList<Track>();
    private String queueToken = "";
    private final Handler handler = new Handler();

    private QueueStore queueStore;
    private TrackFileCache trackCache;
    private SongCache.Lease playbackCache;
    private PlaybackPreferences playbackOptions;
    private QueuePreloader preloader;
    private static PlaybackService runningService;
    private float fadeGain = 1f;
    private Runnable fadeTick;
    private int fadeSerial, pendingSwitch = -1;
    private boolean fadingOut;
    private final Runnable endFadeTick = new Runnable() {
        @Override public void run() {
            if (!isPlaying() || playbackOptions.fadeMs() == 0) return;
            int remaining = getDurationMs() - getPositionMs();
            if (pendingSwitch < 0 && !fadingOut && remaining > 0 && remaining <= playbackOptions.fadeMs()) {
                animateGain(0f, remaining, null);
            }
            handler.postDelayed(this, 200L);
        }
    };
    private HistoryStore historyStore;
    private MediaPlayer player;
    private AudioManager audioManager;
    private Object audioFocusRequest;
    private int lastFocusResult;
    private int focusState;
    private boolean focusRequestActive;
    private boolean restartPending;
    private boolean initializing;
    private final List<String> audioEvents = new ArrayList<String>();
    private final long audioEventsStartedAt = SystemClock.elapsedRealtime();
    private MediaControlSession mediaControlSession;
    private int currentIndex = -1;
    private int playMode = MODE_SEQUENCE;
    private int pendingSeekMs;
    private boolean prepared;
    private boolean loading;
    private boolean playWhenPrepared = true;
    private boolean resumeOnFocusGain;
    private boolean fallbackToMp3;
    private String legacyAlternateCodec;
    private String activeCodec;
    private boolean fallbackToFile;
    private OriginalAudioStream originalStream;
    private OriginalAudioStream localStream;
    private AudioFile localRetryFile;
    private boolean usingTranscode;
    private int audioMode;
    private int mp3BitrateKbps;
    private ProgressiveAudioFile progressiveAudio;
    private AudioFile downloadedAudio;
    private boolean waitingForSeek;
    private String audioDescription = "";
    private String errorMessage = "";
    private long prepareStartedAtMs;
    private final ExecutorService trackLoader = Executors.newCachedThreadPool();
    private Future<?> loadTask;
    private int loadGeneration;

    private final Runnable persistProgress = new Runnable() {
        @Override
        public void run() {
            persist();
            if (isPlaying()) handler.postDelayed(this, 5000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        initializing = true;
        restartPending = getSharedPreferences("playback_settings", MODE_PRIVATE).getBoolean("restart_playing", false);
        queueStore = new QueueStore(this);
        trackCache = new TrackFileCache(this);
        playbackOptions = new PlaybackPreferences(this);
        preloader = new QueuePreloader(this, trackCache);
        runningService = this;
        historyStore = new HistoryStore(this);
        playMode = getSharedPreferences("playback_settings", MODE_PRIVATE)
                .getInt("play_mode", MODE_SEQUENCE);
        audioMode = getSharedPreferences("playback_settings", MODE_PRIVATE)
                .getInt("audio_mode", AudioCompatibility.AUTO);
        if (audioMode < 0 || audioMode > AudioCompatibility.NAS_FLAC) audioMode = AudioCompatibility.AUTO;
        mp3BitrateKbps = getSharedPreferences("playback_settings", MODE_PRIVATE).getInt("mp3_bitrate", 192);
        if (!validMp3Bitrate(mp3BitrateKbps)) mp3BitrateKbps = 192;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
        restoreQueue();
        createPlayer();
        registerMediaControls();
        initializing = false;
        AppLog.info("playback.service_created", "queue=" + queue.size() + " recover=" + restartPending);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (intent == null && restartPending && !queue.isEmpty()) {
            restartPending = false;
            recordAudioEvent("系统重建播放服务，恢复上次播放位置");
            showNotification();
            play();
        }
        if (action != null && queue.isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_STOP.equals(action)) stopPlayback();
        return hasPlaybackIntent() ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        AppLog.info("playback.service_destroyed", "playing=" + isPlaying() + " loading=" + loading);
        cancelFade();
        preloader.close();
        if (runningService == this) runningService = null;
        persist();
        queueStore.close();
        loadGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        closeOriginalStream();
        trackLoader.shutdownNow();
        abandonAudioFocus();
        unregisterMediaControls();
        if (player != null) {
            player.reset();
            player.release();
            player = null;
        }
        releasePlaybackCache();
        super.onDestroy();
    }

    public String setQueueAndPlay(List<Track> tracks, int index) {
        if (tracks == null || tracks.isEmpty() || index < 0 || index >= tracks.size()) return "";
        queueToken = UUID.randomUUID().toString();
        preloader.clear();
        queue.clear();
        queue.addAll(tracks);
        currentIndex = index;
        queueStore.saveQueue(queue);
        pendingSeekMs = 0;
        legacyAlternateCodec = null;
        fallbackToMp3 = false;
        fallbackToFile = false;
        persist();
        prepareCurrent(true);
        return queueToken;
    }

    /** Adds later pages only to the queue that requested them, without restarting playback. */
    public boolean appendToQueue(String expectedToken, List<Track> tracks) {
        if (expectedToken == null || expectedToken.length() == 0 || !expectedToken.equals(queueToken)) return false;
        if (tracks == null || tracks.isEmpty()) return true;
        queue.addAll(tracks);
        queueStore.saveQueue(queue);
        persist();
        updatePlaybackState();
        if (prepared) schedulePreload();
        return true;
    }

    public void playOnLaunch() {
        if (playbackOptions.autoPlayOnLaunch() && !hasPlaybackIntent() && !loading && !focusBlocksPlayback()) play();
    }

    public void toggle() {
        if (isPlaying()) pause(); else play();
    }

    public void play() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        recordAudioEvent("收到播放指令");
        resumeOnFocusGain = false;
        // An explicit play action may request focus again; automatic resume uses the existing grant.
        if (focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) focusState = 0;
        ensureStartedService();
        playWhenPrepared = true;
        if (focusBlocksPlayback()) {
            resumeOnFocusGain = true;
            playWhenPrepared = false;
            updatePlaybackState(); showNotification(); return;
        }
        if (loading) {
            updatePlaybackState();
            showNotification();
            return;
        }
        if (!prepared) {
            prepareCurrent(true);
            return;
        }
        if (requestAudioFocus()) {
            startPreparedPlayback();
        }
    }

    private void startPreparedPlayback() {
        cancelFade();
        player.start();
        watchTrackEnd();
        errorMessage = "";
        updatePlaybackState();
        showNotification();
        handler.removeCallbacks(persistProgress);
        handler.postDelayed(persistProgress, 5000L);
    }

    public void pause() {
        recordAudioEvent("收到暂停指令（界面或媒体控制）");
        resumeOnFocusGain = false;
        pauseInternal(true);
    }

    private void pauseInternal(boolean releaseAudioFocus) {
        cancelFade();
        playWhenPrepared = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        if (releaseAudioFocus) abandonAudioFocus();
        persist();
        updatePlaybackState();
        showNotification();
        handler.removeCallbacks(persistProgress);
    }

    public void next() {
        int nextIndex = nextIndex(pendingSwitch >= 0 ? pendingSwitch : currentIndex);
        if (nextIndex >= 0) {
            switchTo(nextIndex);
        } else {
            pause();
        }
    }

    public void previous() {
        if (getPositionMs() > 5000) {
            seekTo(0);
        } else if (!queue.isEmpty()) {
            int index;
            if (playMode == MODE_SHUFFLE && queue.size() > 1) {
                index = randomIndexExceptCurrent();
            } else if (currentIndex > 0) {
                index = currentIndex - 1;
            } else if (playMode == MODE_REPEAT_ALL) {
                index = queue.size() - 1;
            } else {
                seekTo(0);
                return;
            }
            switchTo(index);
        }
    }

    public void playAt(int index) {
        if (index < 0 || index >= queue.size()) return;
        switchTo(index);
    }

    private void switchTo(final int index) {
        boolean fade = isPlaying() && playbackOptions.fadeMs() > 0;
        float from = fadeGain;
        cancelFade();
        if (fade) {
            fadeGain = from;
            pendingSwitch = index;
            animateGain(0f, playbackOptions.fadeMs(), new Runnable() {
                @Override public void run() { commitSwitch(index); }
            });
        } else commitSwitch(index);
    }

    private void commitSwitch(int index) {
        pendingSwitch = -1;
        currentIndex = index;
        pendingSeekMs = 0;
        legacyAlternateCodec = null;
        fallbackToMp3 = false;
        fallbackToFile = false;
        prepareCurrent(true);
    }

    public List<Track> getQueueSnapshot() {
        return new ArrayList<Track>(queue);
    }

    public int getAudioMode() { return audioMode; }

    public int getMp3BitrateKbps() { return mp3BitrateKbps; }

    private static boolean validMp3Bitrate(int value) {
        return value == 128 || value == 192 || value == 256 || value == 320;
    }

    public void setMp3BitrateKbps(int value) {
        if (!validMp3Bitrate(value) || value == mp3BitrateKbps) return;
        mp3BitrateKbps = value;
        preloader.clear();
        getSharedPreferences("playback_settings", MODE_PRIVATE).edit().putInt("mp3_bitrate", value).apply();
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        if (current != null && (fallbackToMp3 || "mp3".equals(AudioCompatibility.transcodeCodec(current, audioMode, Build.VERSION.SDK_INT)))) {
            boolean resume = hasPlaybackIntent();
            pendingSeekMs = getPositionMs();
            prepareCurrent(resume);
        } else if (prepared) schedulePreload();
    }

    public void setAudioMode(int mode) {
        if (mode < 0 || mode > AudioCompatibility.NAS_FLAC || mode == audioMode) return;
        boolean resume = hasPlaybackIntent();
        pendingSeekMs = getPositionMs();
        audioMode = mode;
        preloader.clear();
        legacyAlternateCodec = null;
        fallbackToMp3 = false;
        fallbackToFile = false;
        getSharedPreferences("playback_settings", MODE_PRIVATE).edit().putInt("audio_mode", mode).apply();
        if (currentIndex >= 0 && currentIndex < queue.size()) prepareCurrent(resume);
    }

    @SuppressWarnings("deprecation")
    public String getAudioDiagnostics() {
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        return "Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）\n"
                + "播放选择：" + AudioCompatibility.label(audioMode) + "\n"
                + "MP3 转码码率：" + mp3BitrateKbps + " kbps\n"
                + "当前音频：" + (audioDescription.length() == 0 ? "尚未准备" : audioDescription) + "\n"
                + "源文件：" + (current == null ? "无" : current.qualityLabel()
                        + (current.channels > 0 ? " / " + current.channels + "声道" : "")) + "\n"
                + "媒体音量：" + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                + " / " + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) + "\n"
                + "音频焦点：" + audioFocusStatus() + "\n"
                + "等待自动恢复：" + (resumeOnFocusGain ? "是" : "否") + "\n"
                + "音频缓存：" + (originalStream != null ? "原码 HTTP 流式播放，支持 Range 定位"
                        : localStream != null ? "本机 HTTP 完整文件" : progressiveAudio == null ? "完整文件模式"
                        : progressiveAudio.isComplete() ? "下载已完成" : "边下载边播放，缓冲 " + progressiveAudio.availableBytes() / 1024 + " KB") + "\n"
                + "蓝牙媒体通道：" + (audioManager.isBluetoothA2dpOn() ? "系统报告已启用" : "系统未报告启用") + "\n"
                + "最近音频事件（服务启动后秒数）：\n" + recentAudioEvents() + "\n"
                + "若进度正常但听不到声音，可检查媒体音量、车机音源和蓝牙输出，或切换 NAS MP3 再试。";
    }

    public int cyclePlayMode() {
        playMode = (playMode + 1) % 4;
        getSharedPreferences("playback_settings", MODE_PRIVATE).edit()
                .putInt("play_mode", playMode).commit();
        schedulePreload();
        return playMode;
    }

    public String getPlayModeLabel() {
        if (playMode == MODE_REPEAT_ALL) return "列表循环";
        if (playMode == MODE_REPEAT_ONE) return "单曲循环";
        if (playMode == MODE_SHUFFLE) return "随机播放";
        return "顺序播放";
    }

    public void seekTo(int positionMs) {
        cancelFade();
        if (progressiveAudio != null) {
            // Unknown-length MP3/FLAC is not seekable on some native players.
            // Retain the current download and reopen its completed file for a reliable seek.
            boolean resume = hasPlaybackIntent();
            pendingSeekMs = Math.max(0, Math.min(positionMs, getDurationMs()));
            closeProgressiveAudio();
            player.reset();
            prepared = false;
            loading = true;
            playWhenPrepared = resume;
            waitingForSeek = true;
            if (downloadedAudio != null) {
                waitingForSeek = false;
                openAudio(loadGeneration, downloadedAudio, null);
            }
            updatePlaybackState();
            showNotification();
            return;
        }
        if (prepared && player != null) {
            int safe = Math.max(0, Math.min(positionMs, getDurationMs()));
            player.seekTo(safe);
            persist();
            watchTrackEnd();
        } else {
            pendingSeekMs = Math.max(0, positionMs);
        }
    }

    public PlaybackState snapshot() {
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        return new PlaybackState(current, isPlaying(), loading, getPositionMs(), getDurationMs(),
                errorMessage, playMode, currentIndex, queue.size(), audioDescription,
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ? "系统媒体音量为 0"
                        : resumeOnFocusGain ? "已临时暂停，等待其他音频结束" : "");
    }

    public void clearQueueAndStop() {
        preloader.clear();
        queueToken = "";
        stopPlayback();
        queue.clear();
        currentIndex = -1;
        pendingSeekMs = 0;
        queueStore.clear();
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        prepared = true;
        loading = false;
        Log.i(TAG, "Prepared stream in " + (System.currentTimeMillis() - prepareStartedAtMs) + " ms");
        recordAudioEvent("播放器就绪 · " + (System.currentTimeMillis() - prepareStartedAtMs) + " ms");
        if (pendingSeekMs > 0) mediaPlayer.seekTo(pendingSeekMs);
        pendingSeekMs = 0;
        if (playWhenPrepared) {
            fadeGain = playbackOptions.fadeMs() > 0 ? 0f : 1f;
            if (requestAudioFocus()) {
                mediaPlayer.start();
                animateGain(1f, playbackOptions.fadeMs(), null);
                watchTrackEnd();
            }
        }
        schedulePreload();
        if (currentIndex >= 0 && currentIndex < queue.size()) {
            historyStore.add(queue.get(currentIndex));
        }
        updatePlaybackState();
        updateMetadata();
        showNotification();
        handler.removeCallbacks(persistProgress);
        handler.postDelayed(persistProgress, 5000L);
    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        cancelFade();
        if (playMode == MODE_REPEAT_ONE) {
            pendingSeekMs = 0;
            prepareCurrent(true);
        } else {
            int following = nextIndex();
            if (following >= 0) commitSwitch(following); else pause();
        }
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
        cancelFade();
        AppLog.warn("playback.media_error", "what=" + what + " extra=" + extra);
        if (!prepared && retryLocalFile()) return true;
        if (!prepared && localStream != null && retryLegacyTranscode()) return true;
        if (retryWithOriginalFile()) return true;
        if (retryWithNasMp3()) return true;
        closeOriginalStream();
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        player.reset();
        releasePlaybackCache();
        prepared = false;
        loading = false;
        resumeOnFocusGain = false;
        playWhenPrepared = false;
        errorMessage = "当前系统无法播放此音频（" + what + "/" + extra + "），可点击音质切换 NAS 转码";
        updatePlaybackState();
        showNotification();
        return true;
    }

    private String codecForCurrent(Track track) {
        if (legacyAlternateCodec != null) return legacyAlternateCodec;
        return fallbackToMp3 ? "mp3" : AudioCompatibility.transcodeCodec(track, audioMode, Build.VERSION.SDK_INT);
    }

    private boolean retryLegacyTranscode() {
        if (Build.VERSION.SDK_INT >= 23 || !usingTranscode || legacyAlternateCodec != null) return false;
        legacyAlternateCodec = "mp3".equals(activeCodec) ? "flac" : "mp3";
        recordAudioEvent("车机无法打开本次转码，改试 NAS " + legacyAlternateCodec.toUpperCase(java.util.Locale.US));
        prepareCurrent(playWhenPrepared || resumeOnFocusGain);
        return true;
    }

    private boolean retryWithNasMp3() {
        if (audioMode != AudioCompatibility.AUTO || usingTranscode || fallbackToMp3) return false;
        if (progressiveAudio != null && progressiveAudio.hasFailure()) return false;
        pendingSeekMs = getPositionMs();
        fallbackToMp3 = true;
        prepareCurrent(playWhenPrepared || resumeOnFocusGain);
        return true;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        recordAudioEvent(focusChangeLabel(focusChange));
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pauseInternal(false);
            abandonAudioFocus();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // Navigation/firmware may report the same loss more than once after playback has paused.
            resumeOnFocusGain = hasPlaybackIntent();
            focusState = AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
            pauseInternal(false);
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            // A duck callback cannot release a previous, stricter transient pause (for example a call).
            if (focusState != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) focusState = focusChange;
            if (playbackOptions.pauseForNavigation()) {
                resumeOnFocusGain = hasPlaybackIntent();
                pauseInternal(false);
            } else applyPlayerGain();
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            // Ignore a queued gain after a manual pause/stop or permanent loss abandoned the request.
            if (!focusRequestActive && !resumeOnFocusGain) return;
            focusRequestActive = true;
            focusState = AudioManager.AUDIOFOCUS_GAIN;
            lastFocusResult = AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            applyPlayerGain();
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                playWhenPrepared = true;
                if (prepared) startPreparedPlayback();
            }
        }
    }

    private boolean hasPlaybackIntent() {
        return resumeOnFocusGain || isPlaying() || (loading && playWhenPrepared);
    }

    private String audioFocusStatus() {
        if (focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && (focusRequestActive || resumeOnFocusGain))
            return "暂时失焦，等待归还";
        if (focusRequestActive && focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return "允许混音，音乐音量 25%";
        return focusRequestActive && lastFocusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "已获得" : "未持有";
    }

    private static String focusChangeLabel(int value) {
        if (value == AudioManager.AUDIOFOCUS_LOSS) return "永久失焦 (-1)";
        if (value == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) return "临时失焦 (-2)";
        if (value == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return "允许降低音量混音 (-3)";
        if (value == AudioManager.AUDIOFOCUS_GAIN) return "焦点归还 (+1)";
        return "焦点事件 (" + value + ")";
    }

    private void recordAudioEvent(String event) {
        if (audioEvents.size() >= 24) audioEvents.remove(0);
        audioEvents.add((SystemClock.elapsedRealtime() - audioEventsStartedAt) / 1000 + "s · " + event);
        Log.i(TAG, "Audio: " + event);
        AppLog.info("playback.audio", event);
    }

    private String recentAudioEvents() {
        StringBuilder text = new StringBuilder();
        for (String event : audioEvents) text.append(event).append('\n');
        return text.length() == 0 ? "暂无\n" : text.toString();
    }

    private void prepareCurrent(boolean autoPlay) {
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        cancelFade();
        final int generation = ++loadGeneration;
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        closeOriginalStream();
        downloadedAudio = null;
        waitingForSeek = false;
        player.reset();
        releasePlaybackCache();
        prepared = false;
        loading = false;
        final Track track = queue.get(currentIndex);
        if (track.accessStatus == 3) {
            resumeOnFocusGain = false;
            playWhenPrepared = false;
            errorMessage = "该歌曲文件已失效";
            updatePlaybackState();
            return;
        }
        ensureStartedService();
        if (focusBlocksPlayback()) {
            resumeOnFocusGain = autoPlay;
            playWhenPrepared = false;
        } else {
            resumeOnFocusGain = false;
            playWhenPrepared = autoPlay;
        }
        prepared = false;
        loading = true;
        errorMessage = "";
        final String transcodeCodec = codecForCurrent(track);
        activeCodec = transcodeCodec;
        usingTranscode = transcodeCodec.length() > 0;
        playbackCache = trackCache.protectTrack(track, transcodeCodec, mp3BitrateKbps);
        preloader.retainCurrent(trackCache.requestKey(track, transcodeCodec, mp3BitrateKbps));
        audioDescription = usingTranscode ? "NAS 转码 · " + transcodeCodec.toUpperCase(java.util.Locale.US)
                : "原始 · " + track.qualityLabel();
        persist();
        updatePlaybackState();
        updateMetadata();
        showNotification();
        prepareStartedAtMs = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT < 23 && !usingTranscode && !fallbackToFile) {
            recordAudioEvent("开始准备音频：原码直连流式播放");
            loadTask = trackLoader.submit(new Runnable() {
                @Override public void run() {
                    try {
                        final AudioFile prefetched = preloader.await(track, transcodeCodec, mp3BitrateKbps);
                        if (prefetched != null) {
                            handler.post(new Runnable() {
                                @Override public void run() { openAudio(generation, prefetched, null); }
                            });
                            return;
                        }
                        final AudioFile cached = trackCache.cachedOriginal(track);
                        if (cached != null) {
                            handler.post(new Runnable() {
                                @Override public void run() { openAudio(generation, cached, null); }
                            });
                            return;
                        }
                        final OriginalAudioStream stream = new OriginalAudioStream(FnApiClient.get(PlaybackService.this).snapshot(), track, trackCache);
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (generation != loadGeneration) { stream.close(); return; }
                                originalStream = stream;
                                try {
                                    configurePlayerAudio();
                                    player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                                    player.setDataSource(stream.url());
                                    player.prepareAsync();
                                } catch (Exception error) {
                                    if (!retryWithOriginalFile()) failCurrentLoad(generation, error);
                                }
                            }
                        });
                    } catch (final Exception error) {
                        handler.post(new Runnable() {
                            @Override public void run() {
                                if (generation != loadGeneration) return;
                                fallbackToFile = true;
                                prepareCurrent(playWhenPrepared || resumeOnFocusGain);
                            }
                        });
                    }
                }
            });
            return;
        }
        final int requestedBitrate = mp3BitrateKbps;
        final boolean streamPlayback = Build.VERSION.SDK_INT >= 23 && pendingSeekMs <= 0;
        recordAudioEvent(streamPlayback ? "开始准备音频：渐进播放"
                : "开始准备音频：完整文件模式（" + (pendingSeekMs > 0 ? "恢复播放位置" : "API " + Build.VERSION.SDK_INT) + "）");
        loadTask = trackLoader.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final AudioFile prefetched = preloader.await(track, transcodeCodec, requestedBitrate);
                    if (prefetched != null) {
                        handler.post(new Runnable() {
                            @Override public void run() { openAudio(generation, prefetched, null); }
                        });
                        return;
                    }
                    trackCache.getOrDownload(track, transcodeCodec, requestedBitrate,
                            !streamPlayback ? null : new TrackFileCache.ReadyListener() {
                                @Override public void onReady(final ProgressiveAudioFile stream) {
                                    handler.post(new Runnable() {
                                        @Override public void run() { openAudio(generation, stream.audio, stream); }
                                    });
                                }
                            }, new NasTranscodeDownloader.StageListener() {
                                @Override public void onStage(final String stage) {
                                    handler.post(new Runnable() {
                                        @Override public void run() {
                                            if (generation == loadGeneration) recordAudioEvent(stage);
                                        }
                                    });
                                }
                            }, new TrackFileCache.CompleteListener() {
                                @Override public void onComplete(final AudioFile audio) {
                                    handler.post(new Runnable() {
                                        @Override public void run() {
                                            if (generation != loadGeneration) return;
                                            downloadedAudio = audio;
                                            if (!streamPlayback || waitingForSeek) {
                                                waitingForSeek = false;
                                                openAudio(generation, audio, null);
                                            }
                                        }
                                    });
                                }
                            });
                } catch (final Exception error) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            failCurrentLoad(generation, error);
                        }
                    });
                }
            }
        });
    }

    private void openAudio(int generation, AudioFile audio, ProgressiveAudioFile stream) {
        if (generation != loadGeneration) {
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            return;
        }
        FileInputStream input = null;
        try {
            localRetryFile = stream == null && Build.VERSION.SDK_INT < 23 ? audio : null;
            recordAudioEvent("交给播放器 · " + (System.currentTimeMillis() - prepareStartedAtMs) + " ms");
            audioDescription = audio.description();
            recordAudioEvent((stream == null ? "完整文件" : "渐进音频") + " · " + audioDescription + " · " + audio.file.length() + " B");
            progressiveAudio = stream;
            configurePlayerAudio();
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            if (stream != null) AudioDataSourceApi23.set(player, stream);
            else {
                input = new FileInputStream(audio.file);
                long length = input.getChannel().size();
                if (length <= 0) throw new java.io.IOException("音频文件为空");
                player.setDataSource(input.getFD(), 0, length);
            }
            player.prepareAsync();
        } catch (Exception error) {
            if (!retryLocalFile() && !retryWithNasMp3()) failCurrentLoad(generation, error);
        } finally {
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
    }

    private void closeProgressiveAudio() {
        if (progressiveAudio != null) {
            try { progressiveAudio.close(); } catch (Exception ignored) { }
            progressiveAudio = null;
        }
    }

    private boolean retryLocalFile() {
        final AudioFile audio = localRetryFile;
        if (audio == null) return false;
        localRetryFile = null;
        final int generation = loadGeneration;
        recordAudioEvent("车机无法直接打开文件，使用本机 HTTP 重试同一份音频");
        loading = true;
        prepared = false;
        player.reset();
        trackLoader.execute(new Runnable() {
            @Override public void run() {
                try {
                    final OriginalAudioStream server = new OriginalAudioStream(audio);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (generation != loadGeneration) { server.close(); return; }
                            localStream = server;
                            try {
                                configurePlayerAudio();
                                player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                                player.setDataSource(server.url());
                                player.prepareAsync();
                            } catch (Exception error) { localRetryFailed(generation, error); }
                        }
                    });
                } catch (final Exception error) {
                    handler.post(new Runnable() {
                        @Override public void run() { localRetryFailed(generation, error); }
                    });
                }
            }
        });
        return true;
    }

    private void localRetryFailed(int generation, Exception error) {
        if (generation == loadGeneration && !retryLegacyTranscode() && !retryWithNasMp3()) failCurrentLoad(generation, error);
    }

    private void closeOriginalStream() {
        localRetryFile = null;
        if (localStream != null) { localStream.close(); localStream = null; }
        if (originalStream != null) { originalStream.close(); originalStream = null; }
    }

    private void releasePlaybackCache() {
        if (playbackCache != null) { playbackCache.close(); playbackCache = null; }
    }

    private boolean retryWithOriginalFile() {
        if (originalStream == null || fallbackToFile) return false;
        recordAudioEvent("原码流式读取失败，回退完整文件播放");
        pendingSeekMs = getPositionMs();
        fallbackToFile = true;
        prepareCurrent(playWhenPrepared || resumeOnFocusGain);
        return true;
    }

    private void failCurrentLoad(int generation, Exception error) {
        if (generation != loadGeneration) return;
        recordAudioEvent("播放准备失败：" + error.getMessage());
        loading = false;
        prepared = false;
        resumeOnFocusGain = false;
        playWhenPrepared = false;
        closeProgressiveAudio();
        closeOriginalStream();
        if (player != null) player.reset();
        releasePlaybackCache();
        errorMessage = "无法打开音频：" + safeMessage(error);
        Log.e(TAG, errorMessage, error);
        AppLog.error("playback.prepare_failed", error);
        updatePlaybackState();
        showNotification();
    }

    private void createPlayer() {
        player = new MediaPlayer();
        configurePlayerAudio();
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
    }

    private void configurePlayerAudio() {
        if (Build.VERSION.SDK_INT >= 21) MediaPlayerAudioApi21.configure(player);
        else player.setAudioStreamType(AudioManager.STREAM_MUSIC);
    }

    private boolean requestAudioFocus() {
        if (focusBlocksPlayback()) return false;
        if (focusRequestActive && (focusState == AudioManager.AUDIOFOCUS_GAIN
                || focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            if (player != null) {
                applyPlayerGain();
            }
            return true;
        }
        showNotification();
        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest == null) audioFocusRequest = AudioFocusApi26.create(this);
            lastFocusResult = AudioFocusApi26.request(audioManager, audioFocusRequest);
        } else {
            lastFocusResult = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        boolean granted = lastFocusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        focusRequestActive = granted;
        focusState = granted ? AudioManager.AUDIOFOCUS_GAIN : 0;
        recordAudioEvent(granted ? "申请焦点成功" : "申请焦点被拒绝");
        if (granted) applyPlayerGain();
        if (!granted) errorMessage = "未获得媒体音频焦点，请结束其他音频或通话后再试";
        return granted;
    }

    private boolean focusBlocksPlayback() {
        return focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && playbackOptions.pauseForNavigation();
    }

    private void abandonAudioFocus() {
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) AudioFocusApi26.abandon(audioManager, audioFocusRequest);
            else audioManager.abandonAudioFocus(this);
        }
        lastFocusResult = 0;
        focusState = 0;
        focusRequestActive = false;
    }

    private boolean isPlaying() {
        try {
            return prepared && player != null && player.isPlaying();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private int getPositionMs() {
        try {
            return prepared && player != null ? player.getCurrentPosition() : pendingSeekMs;
        } catch (IllegalStateException ignored) {
            return pendingSeekMs;
        }
    }

    private int getDurationMs() {
        try {
            if (prepared && player != null && player.getDuration() > 0) return player.getDuration();
        } catch (IllegalStateException ignored) {
        }
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        return current == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current.durationMs);
    }

    private void stopPlayback() {
        cancelFade();
        preloader.clear();
        recordAudioEvent("停止播放");
        resumeOnFocusGain = false;
        playWhenPrepared = false;
        int stoppedAt = getPositionMs();
        loadGeneration++;
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        closeOriginalStream();
        if (player != null) {
            player.reset();
        }
        releasePlaybackCache();
        prepared = false;
        loading = false;
        waitingForSeek = false;
        downloadedAudio = null;
        pendingSeekMs = stoppedAt;
        persist();
        abandonAudioFocus();
        stopForeground(true);
        getSharedPreferences("playback_settings", MODE_PRIVATE).edit().putBoolean("restart_playing", false).apply();
        stopSelf();
    }

    private int nextIndex() {
        return nextIndex(currentIndex);
    }

    private int nextIndex(int index) {
        if (queue.isEmpty()) return -1;
        if (playMode == MODE_SHUFFLE && queue.size() > 1) return randomIndexExceptCurrent();
        if (index + 1 < queue.size()) return index + 1;
        if (playMode == MODE_REPEAT_ALL || playMode == MODE_SHUFFLE) return 0;
        return -1;
    }

    private int randomIndexExceptCurrent() {
        if (queue.size() <= 1) return queue.isEmpty() ? -1 : 0;
        int candidate = new Random().nextInt(queue.size() - 1);
        if (candidate >= currentIndex) candidate++;
        return candidate;
    }

    private void ensureStartedService() {
        Intent intent = new Intent(this, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= 26) ServiceStartApi26.start(this, intent);
        else startService(intent);
    }

    public void refreshPlaybackOptions() {
        cancelFade();
        preloader.clear();
        if (focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK && playbackOptions.pauseForNavigation()) {
            resumeOnFocusGain = hasPlaybackIntent();
            pauseInternal(false);
        }
        if (prepared) { schedulePreload(); watchTrackEnd(); }
    }

    public static void resetForAccountSwitch(Context context) {
        context.getSharedPreferences("playback_settings", MODE_PRIVATE).edit().putBoolean("restart_playing", false).apply();
        if (runningService != null) runningService.clearQueueAndStop();
        else {
            QueueStore storedQueue = new QueueStore(context);
            storedQueue.clear();
            storedQueue.close();
        }
        new HistoryStore(context).clear();
    }

    private void schedulePreload() {
        java.util.List<QueuePreloader.Request> upcoming = new ArrayList<QueuePreloader.Request>();
        if (playbackOptions.preloadCount() > 0 && currentIndex >= 0
                && (playMode == MODE_SEQUENCE || playMode == MODE_REPEAT_ALL)) {
            for (int step = 1; step < queue.size() && upcoming.size() < playbackOptions.preloadCount(); step++) {
                int index = currentIndex + step;
                if (index >= queue.size()) {
                    if (playMode != MODE_REPEAT_ALL) break;
                    index %= queue.size();
                }
                Track track = queue.get(index);
                if (track.accessStatus == 3) continue;
                upcoming.add(new QueuePreloader.Request(track,
                        AudioCompatibility.transcodeCodec(track, audioMode, Build.VERSION.SDK_INT), mp3BitrateKbps, trackCache));
            }
        }
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        String codec = current == null ? "" : codecForCurrent(current);
        preloader.plan(upcoming, current == null ? null : trackCache.requestKey(current, codec, mp3BitrateKbps));
    }

    private void applyPlayerGain() {
        if (player == null) return;
        float gain = fadeGain * (focusState == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? .25f : 1f);
        player.setVolume(gain, gain);
    }

    private void cancelFade() {
        fadeSerial++;
        if (fadeTick != null) handler.removeCallbacks(fadeTick);
        handler.removeCallbacks(endFadeTick);
        fadeTick = null;
        pendingSwitch = -1;
        fadingOut = false;
        fadeGain = 1f;
        applyPlayerGain();
    }

    private void animateGain(final float target, final int durationMs, final Runnable after) {
        if (fadeTick != null) handler.removeCallbacks(fadeTick);
        final int serial = ++fadeSerial;
        final float from = fadeGain;
        final long started = SystemClock.uptimeMillis();
        fadingOut = target < from;
        fadeTick = new Runnable() {
            @Override public void run() {
                if (serial != fadeSerial || player == null) return;
                float fraction = durationMs <= 0 ? 1f : Math.min(1f, (SystemClock.uptimeMillis() - started) / (float) durationMs);
                fadeGain = from + (target - from) * fraction;
                applyPlayerGain();
                if (fraction >= 1f) {
                    fadeTick = null;
                    if (after != null) after.run();
                } else handler.postDelayed(this, 20L);
            }
        };
        fadeTick.run();
    }

    private void watchTrackEnd() {
        handler.removeCallbacks(endFadeTick);
        if (playbackOptions.fadeMs() > 0 && isPlaying()) handler.postDelayed(endFadeTick, 200L);
    }

    private void persist() {
        queueStore.savePosition(currentIndex, getPositionMs());
    }

    private void restoreQueue() {
        QueueStore.RestoredQueue restored = queueStore.restore();
        queue.addAll(restored.tracks);
        currentIndex = restored.index;
        pendingSeekMs = restored.positionMs;
    }

    private void registerMediaControls() {
        mediaControlSession = MediaControlSessions.create(this,
                new ComponentName(this, MediaButtonReceiver.class),
                new MediaControlSession.Callback() {
                    @Override public void onPlay() { play(); }
                    @Override public void onPause() { pause(); }
                    @Override public void onToggle() { toggle(); }
                    @Override public void onNext() { next(); }
                    @Override public void onPrevious() { previous(); }
                    @Override public void onSeekTo(long positionMs) {
                        seekTo((int) Math.min(Integer.MAX_VALUE, Math.max(0L, positionMs)));
                    }
                });
        updatePlaybackState();
        updateMetadata();
    }

    private void unregisterMediaControls() {
        if (mediaControlSession != null) {
            mediaControlSession.release();
            mediaControlSession = null;
        }
    }

    private void updatePlaybackState() {
        if (!initializing) getSharedPreferences("playback_settings", MODE_PRIVATE).edit()
                .putBoolean("restart_playing", hasPlaybackIntent()).apply();
        updateMediaControlSession();
    }

    private void updateMetadata() {
        updateMediaControlSession();
    }

    private void updateMediaControlSession() {
        if (mediaControlSession == null) return;
        Track track = currentIndex >= 0 && currentIndex < queue.size()
                ? queue.get(currentIndex) : null;
        mediaControlSession.update(track, isPlaying(), loading, getPositionMs(), getDurationMs());
    }

    @SuppressWarnings("deprecation")
    private void showNotification() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        Track track = queue.get(currentIndex);
        Intent open = new Intent(this, PlayerActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 0, open, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? NotificationApi26.newBuilder(this, NOTIFICATION_CHANNEL)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(track.title.length() == 0 ? getString(R.string.unknown_title) : track.title)
                .setContentText(track.artist.length() == 0 ? getString(R.string.unknown_artist) : track.artist)
                .setContentIntent(content)
                .setOngoing(isPlaying())
                .getNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationApi26.createChannel(this, NOTIFICATION_CHANNEL);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }

    public final class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    public static final class PlaybackState {
        public final Track track;
        public final boolean playing;
        public final boolean loading;
        public final int positionMs;
        public final int durationMs;
        public final String error;
        public final int playMode;
        public final int queueIndex;
        public final int queueSize;
        public final String audioDescription;
        public final String audioWarning;

        PlaybackState(Track track, boolean playing, boolean loading, int positionMs, int durationMs,
                      String error, int playMode, int queueIndex, int queueSize, String description, String warning) {
            this.track = track;
            this.playing = playing;
            this.loading = loading;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.error = error;
            this.playMode = playMode;
            this.queueIndex = queueIndex;
            this.queueSize = queueSize;
            this.audioDescription = description;
            this.audioWarning = warning;
        }
    }
}
