package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.update.AppUpdater;
import com.feiniu.carmusic.data.CoverLoader;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.model.Track;
import com.feiniu.carmusic.player.PlaybackService;
import com.feiniu.carmusic.player.AudioCompatibility;
import com.feiniu.carmusic.player.MediaKeyRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PlayerActivity extends Activity {
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private final Handler handler = new Handler();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<TimedLyricLine> lyricLines = new ArrayList<TimedLyricLine>();
    private PlaybackService playback;
    private boolean bound;
    private boolean trackingSeek;
    private String displayedGuid = "";
    private int lyricGeneration;
    private ImageView cover;
    private ImageView backgroundCover;
    private TextView title;
    private TextView artist;
    private TextView status;
    private TextView quality;
    private TextView position;
    private TextView duration;
    private TextView queueStatus;
    private PlayerLyricView lyricView;
    private View playerRoot;
    private View playerHeader;
    private View playerBody;
    private View artPanel;
    private View coverFrame;
    private View headerMeta;
    private View seekSection;
    private View controlBar;
    private int responsiveWidth = -1;
    private int responsiveHeight = -1;
    private PlayerControlButton back;
    private PlayerControlButton toggle;
    private PlayerControlButton mode;
    private PlayerControlButton favorite;
    private PlayerControlButton previous;
    private PlayerControlButton next;
    private PlayerControlButton queue;
    private SeekBar seek;

    private final Runnable update = new Runnable() {
        @Override public void run() {
            updateUi();
            handler.postDelayed(this, 300L);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playback = ((PlaybackService.LocalBinder) service).getService();
            bound = true;
            updateUi();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            bound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_player);
        cover = (ImageView) findViewById(R.id.player_cover);
        backgroundCover = (ImageView) findViewById(R.id.player_background_cover);
        title = (TextView) findViewById(R.id.player_title);
        artist = (TextView) findViewById(R.id.player_artist);
        status = (TextView) findViewById(R.id.player_status);
        quality = (TextView) findViewById(R.id.player_quality);
        position = (TextView) findViewById(R.id.player_position);
        duration = (TextView) findViewById(R.id.player_duration);
        queueStatus = (TextView) findViewById(R.id.player_queue_status);
        lyricView = (PlayerLyricView) findViewById(R.id.player_lyric_view);
        playerRoot = findViewById(R.id.player_root);
        playerHeader = findViewById(R.id.player_header);
        playerBody = findViewById(R.id.player_body);
        artPanel = findViewById(R.id.player_art_panel);
        coverFrame = findViewById(R.id.player_cover_frame);
        headerMeta = findViewById(R.id.player_header_meta);
        seekSection = findViewById(R.id.player_seek_section);
        controlBar = findViewById(R.id.player_control_bar);
        back = (PlayerControlButton) findViewById(R.id.player_back);
        toggle = (PlayerControlButton) findViewById(R.id.player_toggle);
        mode = (PlayerControlButton) findViewById(R.id.player_mode);
        favorite = (PlayerControlButton) findViewById(R.id.player_favorite);
        previous = (PlayerControlButton) findViewById(R.id.player_previous);
        next = (PlayerControlButton) findViewById(R.id.player_next);
        queue = (PlayerControlButton) findViewById(R.id.player_queue);
        seek = (SeekBar) findViewById(R.id.player_seek);

        seek.setProgressDrawable(new PlayerProgressDrawable(getResources()));
        back.configure(PlayerControlButton.BACK, "返回");
        mode.configure(PlayerControlButton.MODE, "顺序");
        previous.configure(PlayerControlButton.PREVIOUS, "上一首");
        toggle.configure(PlayerControlButton.PLAY, "播放");
        next.configure(PlayerControlButton.NEXT, "下一首");
        favorite.configure(PlayerControlButton.HEART, "收藏");
        queue.configure(PlayerControlButton.QUEUE, "队列");

        playerRoot.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                 int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                int height = bottom - top;
                if (width == responsiveWidth && height == responsiveHeight) return;
                responsiveWidth = width;
                responsiveHeight = height;
                playerRoot.post(new Runnable() {
                    @Override public void run() {
                        if (responsiveWidth > 0 && responsiveHeight > 0) {
                            applyResponsivePlayerLayout(responsiveWidth, responsiveHeight);
                        }
                    }
                });
            }
        });

        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (playback != null) playback.previous(); }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (playback != null) playback.toggle(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (playback != null) playback.next(); }
        });
        mode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (playback == null) return;
                playback.cyclePlayMode();
                mode.configure(PlayerControlButton.MODE, shortMode(playback.getPlayModeLabel()));
                Toast.makeText(PlayerActivity.this, playback.getPlayModeLabel(), Toast.LENGTH_SHORT).show();
            }
        });
        favorite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleCurrentFavorite(); }
        });
        queue.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showQueue(); }
        });
        quality.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAudioOptions(); }
        });
        quality.setContentDescription("播放音质，点击选择 NAS 转码，长按查看声音诊断");
        quality.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (playback != null) new AlertDialog.Builder(PlayerActivity.this)
                        .setTitle("声音诊断").setMessage(playback.getAudioDiagnostics())
                        .setPositiveButton("知道了", null).show();
                return true;
            }
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { trackingSeek = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                trackingSeek = false;
                if (playback != null) {
                    PlaybackService.PlaybackState state = playback.snapshot();
                    playback.seekTo((int) ((long) state.durationMs * seekBar.getProgress() / 1000L));
                }
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
        handler.removeCallbacks(update);
        handler.post(update);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(update);
        if (bound) {
            unbindService(connection);
            bound = false;
            playback = null;
        }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        AppUpdater.onResume(this);
    }

    @Override protected void onPause() {
        AppUpdater.onPause(this);
        super.onPause();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AppUpdater.resumePendingInstall(this, requestCode);
    }

    @Override protected void onDestroy() {
        lyricGeneration++;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return MediaKeyRouter.dispatch(this, event) || super.dispatchKeyEvent(event);
    }

    private void updateUi() {
        if (playback == null) return;
        PlaybackService.PlaybackState state = playback.snapshot();
        Track track = state.track;
        if (track == null) {
            title.setText(R.string.not_playing);
            artist.setText("从音乐库选择一首歌曲");
            status.setText("");
            quality.setText("音质");
            queueStatus.setText("");
            toggle.configure(PlayerControlButton.PLAY, "播放");
            toggle.setActive(false);
            lyricView.setError("音乐与远方，都在你的 NAS 里");
            return;
        }
        title.setText(track.title.length() == 0 ? getString(R.string.unknown_title) : track.title);
        String subtitle = track.artist.length() == 0 ? getString(R.string.unknown_artist) : track.artist;
        if (track.album.length() > 0) subtitle += "  ·  " + track.album;
        artist.setText(subtitle);
        quality.setText((state.audioDescription.length() == 0 ? track.qualityLabel() : state.audioDescription) + " ▾");
        if (!track.guid.equals(displayedGuid)) {
            displayedGuid = track.guid;
            CoverLoader.get(this).load(track.coverId, 720, cover, backgroundCover);
            favorite.setActive(track.favorite);
            loadLyrics(track.guid);
        }
        toggle.configure(PlayerControlButton.PLAY,
                state.loading ? "缓冲" : state.playing ? "暂停" : "播放");
        toggle.setActive(state.playing);
        status.setText(state.error.length() > 0 ? state.error
                : state.loading ? "正在准备音频，请稍候…"
                : state.audioWarning.length() > 0 ? state.audioWarning : state.playing ? "正在播放" : "已暂停");
        position.setText(TrackAdapter.formatDuration(state.positionMs));
        duration.setText(TrackAdapter.formatDuration(state.durationMs));
        queueStatus.setText(state.queueSize > 0 ? (state.queueIndex + 1) + " / " + state.queueSize : "");
        mode.configure(PlayerControlButton.MODE, shortMode(playback.getPlayModeLabel()));
        if (!trackingSeek) {
            int progress = state.durationMs <= 0 ? 0
                    : (int) Math.min(1000L, (long) state.positionMs * 1000L / state.durationMs);
            seek.setProgress(progress);
        }
        lyricView.setPlaybackPosition(state.positionMs + 120L, state.playing);
    }

    private void showAudioOptions() {
        if (playback == null) return;
        final String[] labels = {"自动兼容", "原始音质", "NAS 转码 MP3（" + playback.getMp3BitrateKbps() + " kbps）", "NAS 转码 FLAC"};
        new AlertDialog.Builder(this).setTitle("播放音质")
                .setSingleChoiceItems(labels, playback.getAudioMode(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playback.setAudioMode(which);
                        dialog.dismiss();
                        Toast.makeText(PlayerActivity.this, "已选择 " + AudioCompatibility.label(which), Toast.LENGTH_SHORT).show();
                    }
                }).setPositiveButton("MP3 码率", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showMp3Bitrates(); }
                }).setNegativeButton("取消", null).show();
    }

    private void showMp3Bitrates() {
        if (playback == null) return;
        final int[] rates = {128, 192, 256, 320};
        final String[] labels = {"128 kbps（省流量）", "192 kbps（默认）", "256 kbps", "320 kbps（高码率）"};
        int checked = 1;
        for (int i = 0; i < rates.length; i++) if (rates[i] == playback.getMp3BitrateKbps()) checked = i;
        new AlertDialog.Builder(this).setTitle("NAS MP3 转码码率")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playback.setMp3BitrateKbps(rates[which]);
                        dialog.dismiss();
                        Toast.makeText(PlayerActivity.this, "MP3 转码已设为 " + rates[which] + " kbps", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void loadLyrics(final String guid) {
        final int generation = ++lyricGeneration;
        lyricLines.clear();
        lyricView.setError("正在获取歌词…");
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<TimedLyricLine> parsed = parseLrc(
                            FnApiClient.get(PlayerActivity.this).getLyric(guid));
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (generation != lyricGeneration || !guid.equals(displayedGuid)) return;
                            lyricLines.clear();
                            lyricLines.addAll(parsed);
                            lyricView.setLines(lyricLines);
                            PlaybackService.PlaybackState state = playback == null
                                    ? null : playback.snapshot();
                            lyricView.setPlaybackPosition(state == null ? 0L : state.positionMs + 120L,
                                    state != null && state.playing);
                        }
                    });
                } catch (Exception error) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (generation == lyricGeneration) lyricView.setError("暂无歌词");
                        }
                    });
                }
            }
        });
    }

    private void applyResponsivePlayerLayout(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean compact = height < dp(400f) || width < dp(600f);
        int headerHeight = dp(compact ? 54f : 70f);
        int seekHeight = dp(42f);
        int controlsHeight = dp(compact ? 66f : 94f);
        resizeLinearHeight(playerHeader, headerHeight);
        resizeLinearHeight(seekSection, seekHeight);
        resizeLinearHeight(controlBar, controlsHeight);

        int horizontalPadding = dp(compact ? 10f : 18f);
        int topPadding = dp(compact ? 4f : 12f);
        int bottomPadding = dp(compact ? 4f : 8f);
        playerBody.setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding);

        int bodyHeight = Math.max(0, height - headerHeight - seekHeight - controlsHeight);
        int contentHeight = Math.max(0, bodyHeight - topPadding - bottomPadding);
        int bodyWidth = Math.max(0, width - horizontalPadding * 2);
        int coverSize = Math.min(dp(270f), contentHeight);
        int minimumLyricsWidth = dp(compact ? 210f : 300f);
        int desiredPanelWidth = Math.max(coverSize + dp(18f), Math.round(bodyWidth * .30f));
        int panelWidth = Math.min(dp(compact ? 220f : 320f), desiredPanelWidth);
        panelWidth = Math.min(panelWidth, Math.max(0, bodyWidth - minimumLyricsWidth));
        coverSize = Math.min(coverSize, Math.max(0, panelWidth - dp(12f)));
        resizeLinearWidth(artPanel, panelWidth);

        FrameLayout.LayoutParams coverParams =
                (FrameLayout.LayoutParams) coverFrame.getLayoutParams();
        coverParams.width = coverSize;
        coverParams.height = coverSize;
        coverFrame.setLayoutParams(coverParams);

        resizeLinearWidth(back, dp(compact ? 64f : 82f));
        resizeLinearWidth(headerMeta, dp(compact ? 96f : 120f));
        resizeLinearHeight(queueStatus, dp(compact ? 14f : 18f));
        resizeLinearHeight(quality, dp(compact ? 36f : 44f));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 20f : 25f);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 13f : 16f);
        quality.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 13f : 15f);
        queueStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 9f : 11f);
    }

    private static void resizeLinearHeight(View view, int height) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.height = height;
        view.setLayoutParams(params);
    }

    private static void resizeLinearWidth(View view, int width) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.width = width;
        view.setLayoutParams(params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toggleCurrentFavorite() {
        if (playback == null) return;
        final Track track = playback.snapshot().track;
        if (track == null) return;
        final boolean wanted = !track.favorite;
        track.favorite = wanted;
        favorite.setActive(wanted);
        Toast.makeText(this, wanted ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    FnApiClient.get(PlayerActivity.this).setFavorite(track.guid, wanted);
                } catch (final Exception error) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            track.favorite = !wanted;
                            favorite.setActive(track.favorite);
                            Toast.makeText(PlayerActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void showQueue() {
        if (playback == null) return;
        final List<Track> queue = playback.getQueueSnapshot();
        if (queue.isEmpty()) {
            Toast.makeText(this, "播放队列为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[queue.size()];
        for (int i = 0; i < queue.size(); i++) {
            Track track = queue.get(i);
            labels[i] = (i + 1) + ". " + track.title + "  ·  " + track.artist;
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("播放队列 · " + queue.size() + " 首")
                .setSingleChoiceItems(labels, playback.snapshot().queueIndex, null)
                .setNegativeButton("关闭", null)
                .create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface ignored) {
                dialog.getListView().setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                    @Override public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        if (playback != null) playback.playAt(position);
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    private static List<TimedLyricLine> parseLrc(String content) {
        List<TimedLyricLine> result = new ArrayList<TimedLyricLine>();
        if (content == null) return result;
        String[] rows = content.replace("\r", "").split("\n");
        for (String row : rows) {
            Matcher matcher = LRC_TIME.matcher(row);
            String text = matcher.replaceAll("").trim();
            if (text.length() == 0) continue;
            matcher.reset();
            while (matcher.find()) {
                int minutes = parseInt(matcher.group(1));
                int seconds = parseInt(matcher.group(2));
                String fractionText = matcher.group(3);
                int fraction = parseInt(fractionText);
                int millis = fractionText == null ? 0 : fractionText.length() == 1
                        ? fraction * 100 : fractionText.length() == 2 ? fraction * 10 : fraction;
                result.add(new TimedLyricLine((minutes * 60 + seconds) * 1000 + millis, text));
            }
        }
        Collections.sort(result, new Comparator<TimedLyricLine>() {
            @Override public int compare(TimedLyricLine left, TimedLyricLine right) {
                return left.timeMs < right.timeMs ? -1 : left.timeMs == right.timeMs ? 0 : 1;
            }
        });
        return result;
    }

    private static int parseInt(String value) {
        if (value == null) return 0;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String shortMode(String label) {
        if ("列表循环".equals(label)) return "列表循环";
        if ("单曲循环".equals(label)) return "单曲";
        if ("随机播放".equals(label)) return "随机";
        return "顺序";
    }

}
