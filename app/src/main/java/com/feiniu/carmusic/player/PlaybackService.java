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
import android.util.Log;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.HistoryStore;
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
    private HistoryStore historyStore;
    private MediaPlayer player;
    private AudioManager audioManager;
    private Object audioFocusRequest;
    private int lastFocusResult;
    private MediaControlSession mediaControlSession;
    private int currentIndex = -1;
    private int playMode = MODE_SEQUENCE;
    private int pendingSeekMs;
    private boolean prepared;
    private boolean loading;
    private boolean playWhenPrepared = true;
    private boolean resumeOnFocusGain;
    private boolean fallbackToMp3;
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
        queueStore = new QueueStore(this);
        trackCache = new TrackFileCache(this);
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
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
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        persist();
        loadGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        trackLoader.shutdownNow();
        abandonAudioFocus();
        unregisterMediaControls();
        if (player != null) {
            player.reset();
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    public String setQueueAndPlay(List<Track> tracks, int index) {
        if (tracks == null || tracks.isEmpty() || index < 0 || index >= tracks.size()) return "";
        queueToken = UUID.randomUUID().toString();
        queue.clear();
        queue.addAll(tracks);
        currentIndex = index;
        pendingSeekMs = 0;
        fallbackToMp3 = false;
        persist();
        prepareCurrent(true);
        return queueToken;
    }

    /** Adds later pages only to the queue that requested them, without restarting playback. */
    public boolean appendToQueue(String expectedToken, List<Track> tracks) {
        if (expectedToken == null || expectedToken.length() == 0 || !expectedToken.equals(queueToken)) return false;
        if (tracks == null || tracks.isEmpty()) return true;
        queue.addAll(tracks);
        persist();
        updatePlaybackState();
        return true;
    }

    public void toggle() {
        if (isPlaying()) pause(); else play();
    }

    public void play() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        ensureStartedService();
        playWhenPrepared = true;
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
            player.start();
            errorMessage = "";
            updatePlaybackState();
            showNotification();
            handler.removeCallbacks(persistProgress);
            handler.postDelayed(persistProgress, 5000L);
        }
    }

    public void pause() {
        resumeOnFocusGain = false;
        pauseInternal(true);
    }

    private void pauseInternal(boolean releaseAudioFocus) {
        playWhenPrepared = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        if (releaseAudioFocus) abandonAudioFocus();
        persist();
        updatePlaybackState();
        showNotification();
        handler.removeCallbacks(persistProgress);
    }

    public void next() {
        int nextIndex = nextIndex();
        if (nextIndex >= 0) {
            currentIndex = nextIndex;
            pendingSeekMs = 0;
            fallbackToMp3 = false;
            prepareCurrent(true);
        } else {
            pause();
        }
    }

    public void previous() {
        if (getPositionMs() > 5000) {
            seekTo(0);
        } else if (!queue.isEmpty()) {
            if (playMode == MODE_SHUFFLE && queue.size() > 1) {
                currentIndex = randomIndexExceptCurrent();
            } else if (currentIndex > 0) {
                currentIndex--;
            } else if (playMode == MODE_REPEAT_ALL) {
                currentIndex = queue.size() - 1;
            } else {
                seekTo(0);
                return;
            }
            pendingSeekMs = 0;
            fallbackToMp3 = false;
            prepareCurrent(true);
        }
    }

    public void playAt(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentIndex = index;
        pendingSeekMs = 0;
        fallbackToMp3 = false;
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
        getSharedPreferences("playback_settings", MODE_PRIVATE).edit().putInt("mp3_bitrate", value).apply();
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        if (current != null && (fallbackToMp3 || "mp3".equals(AudioCompatibility.transcodeCodec(current, audioMode, Build.VERSION.SDK_INT)))) {
            boolean resume = isPlaying() || (loading && playWhenPrepared);
            pendingSeekMs = getPositionMs();
            prepareCurrent(resume);
        }
    }

    public void setAudioMode(int mode) {
        if (mode < 0 || mode > AudioCompatibility.NAS_FLAC || mode == audioMode) return;
        boolean resume = isPlaying() || (loading && playWhenPrepared);
        pendingSeekMs = getPositionMs();
        audioMode = mode;
        fallbackToMp3 = false;
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
                + "音频焦点：" + (lastFocusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "已获得" : "未获得") + "\n"
                + "音频缓存：" + (progressiveAudio == null ? "完整文件模式"
                        : progressiveAudio.isComplete() ? "下载已完成" : "边下载边播放，缓冲 " + progressiveAudio.availableBytes() / 1024 + " KB") + "\n"
                + "蓝牙媒体通道：" + (audioManager.isBluetoothA2dpOn() ? "系统报告已启用" : "系统未报告启用") + "\n\n"
                + "若进度正常但听不到声音，可检查媒体音量、车机音源和蓝牙输出，或切换 NAS MP3 再试。";
    }

    public int cyclePlayMode() {
        playMode = (playMode + 1) % 4;
        getSharedPreferences("playback_settings", MODE_PRIVATE).edit()
                .putInt("play_mode", playMode).commit();
        return playMode;
    }

    public String getPlayModeLabel() {
        if (playMode == MODE_REPEAT_ALL) return "列表循环";
        if (playMode == MODE_REPEAT_ONE) return "单曲循环";
        if (playMode == MODE_SHUFFLE) return "随机播放";
        return "顺序播放";
    }

    public void seekTo(int positionMs) {
        if (progressiveAudio != null) {
            // Unknown-length MP3/FLAC is not seekable on some native players.
            // Retain the current download and reopen its completed file for a reliable seek.
            boolean resume = isPlaying() || (loading && playWhenPrepared);
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
        } else {
            pendingSeekMs = Math.max(0, positionMs);
        }
    }

    public PlaybackState snapshot() {
        Track current = currentIndex >= 0 && currentIndex < queue.size() ? queue.get(currentIndex) : null;
        return new PlaybackState(current, isPlaying(), loading, getPositionMs(), getDurationMs(),
                errorMessage, playMode, currentIndex, queue.size(), audioDescription,
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ? "系统媒体音量为 0" : "");
    }

    public void clearQueueAndStop() {
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
        if (pendingSeekMs > 0) mediaPlayer.seekTo(pendingSeekMs);
        pendingSeekMs = 0;
        if (playWhenPrepared && requestAudioFocus()) mediaPlayer.start();
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
        if (playMode == MODE_REPEAT_ONE) {
            pendingSeekMs = 0;
            prepareCurrent(true);
        } else {
            next();
        }
    }

    @Override
    public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
        if (retryWithNasMp3()) return true;
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        player.reset();
        prepared = false;
        loading = false;
        errorMessage = "当前系统无法播放此音频（" + what + "/" + extra + "），可点击音质切换 NAS 转码";
        updatePlaybackState();
        showNotification();
        return true;
    }

    private boolean retryWithNasMp3() {
        if (audioMode != AudioCompatibility.AUTO || usingTranscode || fallbackToMp3) return false;
        if (progressiveAudio != null && progressiveAudio.hasFailure()) return false;
        pendingSeekMs = getPositionMs();
        fallbackToMp3 = true;
        prepareCurrent(playWhenPrepared);
        return true;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            pauseInternal(false);
            abandonAudioFocus();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeOnFocusGain = isPlaying() || (loading && playWhenPrepared);
            pauseInternal(false);
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(0.25f, 0.25f);
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1.0f, 1.0f);
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                playWhenPrepared = true;
                if (prepared) play();
            }
        }
    }

    private void prepareCurrent(boolean autoPlay) {
        if (currentIndex < 0 || currentIndex >= queue.size()) return;
        final int generation = ++loadGeneration;
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        downloadedAudio = null;
        waitingForSeek = false;
        player.reset();
        prepared = false;
        loading = false;
        final Track track = queue.get(currentIndex);
        if (track.accessStatus == 3) {
            errorMessage = "该歌曲文件已失效";
            updatePlaybackState();
            return;
        }
        ensureStartedService();
        playWhenPrepared = autoPlay;
        prepared = false;
        loading = true;
        errorMessage = "";
        final String transcodeCodec = fallbackToMp3 ? "mp3"
                : AudioCompatibility.transcodeCodec(track, audioMode, Build.VERSION.SDK_INT);
        usingTranscode = transcodeCodec.length() > 0;
        audioDescription = usingTranscode ? "NAS 转码 · " + transcodeCodec.toUpperCase(java.util.Locale.US)
                : "原始 · " + track.qualityLabel();
        persist();
        updatePlaybackState();
        updateMetadata();
        showNotification();
        prepareStartedAtMs = System.currentTimeMillis();
        final int requestedBitrate = mp3BitrateKbps;
        final boolean streamPlayback = Build.VERSION.SDK_INT >= 23 && pendingSeekMs <= 0;
        loadTask = trackLoader.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final AudioFile audio = trackCache.getOrDownload(track, transcodeCodec, requestedBitrate,
                            !streamPlayback ? null : new TrackFileCache.ReadyListener() {
                                @Override public void onReady(final ProgressiveAudioFile stream) {
                                    handler.post(new Runnable() {
                                        @Override public void run() { openAudio(generation, stream.audio, stream); }
                                    });
                                }
                            });
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
            audioDescription = audio.description();
            progressiveAudio = stream;
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            if (stream != null) AudioDataSourceApi23.set(player, stream);
            else {
                input = new FileInputStream(audio.file);
                player.setDataSource(input.getFD());
            }
            player.prepareAsync();
        } catch (Exception error) {
            if (!retryWithNasMp3()) failCurrentLoad(generation, error);
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

    private void failCurrentLoad(int generation, Exception error) {
        if (generation != loadGeneration) return;
        loading = false;
        prepared = false;
        closeProgressiveAudio();
        if (player != null) player.reset();
        errorMessage = "无法打开音频：" + safeMessage(error);
        Log.e(TAG, errorMessage, error);
        updatePlaybackState();
        showNotification();
    }

    private void createPlayer() {
        player = new MediaPlayer();
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
    }

    private boolean requestAudioFocus() {
        showNotification();
        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest == null) audioFocusRequest = AudioFocusApi26.create(this);
            lastFocusResult = AudioFocusApi26.request(audioManager, audioFocusRequest);
        } else {
            lastFocusResult = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        boolean granted = lastFocusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (granted && player != null) player.setVolume(1.0f, 1.0f);
        if (!granted) errorMessage = "未获得媒体音频焦点，请结束其他音频或通话后再试";
        return granted;
    }

    private void abandonAudioFocus() {
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) AudioFocusApi26.abandon(audioManager, audioFocusRequest);
            else audioManager.abandonAudioFocus(this);
        }
        lastFocusResult = 0;
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
        int stoppedAt = getPositionMs();
        loadGeneration++;
        if (loadTask != null) loadTask.cancel(true);
        closeProgressiveAudio();
        if (player != null) {
            player.reset();
        }
        prepared = false;
        loading = false;
        waitingForSeek = false;
        downloadedAudio = null;
        pendingSeekMs = stoppedAt;
        persist();
        abandonAudioFocus();
        stopForeground(true);
        stopSelf();
    }

    private int nextIndex() {
        if (queue.isEmpty()) return -1;
        if (playMode == MODE_SHUFFLE && queue.size() > 1) return randomIndexExceptCurrent();
        if (currentIndex + 1 < queue.size()) return currentIndex + 1;
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

    private void persist() {
        queueStore.save(queue, currentIndex, getPositionMs());
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
