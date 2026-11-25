package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.UiPreferences;
import com.feiniu.carmusic.data.PlaybackPreferences;
import com.feiniu.carmusic.player.AudioCompatibility;
import com.feiniu.carmusic.player.MediaKeyRouter;
import com.feiniu.carmusic.player.PlaybackService;
import com.feiniu.carmusic.player.SongCache;
import com.feiniu.carmusic.player.CacheDirectories;
import java.io.File;
import java.util.List;
import com.feiniu.carmusic.update.AppUpdater;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Scrollable settings remain reachable on small, old car head units. */
public final class SettingsActivity extends ThemeActivity {
    private UiPreferences preferences;
    private LinearLayout root, content;
    private TextView navigationValue, fontValue, audioValue, bitrateValue;
    private TextView autoPlayValue;
    private TextView cacheSizeValue, cacheUsageValue, cacheDirectoryValue, navigationAudioValue;
    private File pendingCacheParent;
    private TextView themeValue, fadeValue, preloadValue;
    private PlaybackPreferences playbackPreferences;
    private SongCache songCache;
    private final Handler handler = new Handler();
    private final ExecutorService cacheWorker = Executors.newSingleThreadExecutor();
    private boolean destroyed;
    private int cacheGeneration;
    private PlaybackService playback;
    private boolean serviceBinding;
    private final Runnable audioChanged = new Runnable() {
        @Override public void run() { refreshValues(); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (destroyed || !serviceBinding || !(binder instanceof PlaybackService.LocalBinder)) return;
            playback = ((PlaybackService.LocalBinder) binder).getService();
            refreshValues();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (destroyed) return;
            playback = null;
            refreshValues();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new UiPreferences(this);
        songCache = new SongCache(this);
        playbackPreferences = new PlaybackPreferences(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_screen_gradient);
        root.setTag("settings_root");
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        PlayerControlButton back = new PlayerControlButton(this);
        back.configure(PlayerControlButton.BACK, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView heading = text("设置", 22, R.color.text_primary);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        section("显示");
        themeValue = row("明暗主题", "theme", new View.OnClickListener() {
            @Override public void onClick(View v) {
                AudioSettingsDialogs.show(new AlertDialog.Builder(SettingsActivity.this).setTitle("明暗主题")
                        .setSingleChoiceItems(ThemeSettings.LABELS, ThemeSettings.mode(SettingsActivity.this), new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                ThemeSettings.setMode(SettingsActivity.this, which);
                                dialog.dismiss();
                                recreate();
                            }
                        }).setNegativeButton("取消", null));
            }
        });
        navigationValue = row("侧边栏栏目", "navigation", new View.OnClickListener() {
            @Override public void onClick(View v) { showNavigation(); }
        });
        fontValue = row("字体大小", "font", new View.OnClickListener() {
            @Override public void onClick(View v) { showFonts(); }
        });
        section("播放");
        autoPlayValue = row("打开应用自动播放", "auto_play_on_launch", new View.OnClickListener() {
            @Override public void onClick(View v) {
                playbackPreferences.setAutoPlayOnLaunch(!playbackPreferences.autoPlayOnLaunch());
                refreshValues();
            }
        });
        navigationAudioValue = row("导航播报处理", "navigation_audio", new View.OnClickListener() {
            @Override public void onClick(View v) { showNavigationAudio(); }
        });
        fadeValue = row("切歌淡入淡出", "fade", new View.OnClickListener() {
            @Override public void onClick(View v) { showFadeOptions(); }
        });
        preloadValue = row("列表歌曲预加载", "preload", new View.OnClickListener() {
            @Override public void onClick(View v) { showPreloadOptions(); }
        });
        audioValue = row("播放音质", "audio", new View.OnClickListener() {
            @Override public void onClick(View v) {
                AudioSettingsDialogs.showModes(SettingsActivity.this, playback, audioChanged, false);
            }
        });
        bitrateValue = row("MP3 转码码率", "bitrate", new View.OnClickListener() {
            @Override public void onClick(View v) {
                AudioSettingsDialogs.showBitrates(SettingsActivity.this, playback, audioChanged);
            }
        });
        TextView note = text("音质与码率会自动保存；切换时保留当前歌曲和播放位置。播放页音质标签可快速调整。", 12, R.color.text_secondary);
        note.setPadding(dp(4), dp(6), dp(4), dp(8));
        content.addView(note);
        TextView preloadNote = text("预加载会使用当前网络和歌曲缓存，按顺序或列表循环提前准备后续歌曲；关闭或切换账户后停止。淡出后切歌，新歌准备就绪后淡入。", 12, R.color.text_secondary);
        preloadNote.setPadding(dp(4), dp(4), dp(4), dp(8));
        content.addView(preloadNote);
        section("歌曲缓存");
        cacheDirectoryValue = row("缓存目录（TF 卡 / U 盘）", "cache_directory", new View.OnClickListener() {
            @Override public void onClick(View v) { showCacheDirectories(); }
        });
        cacheSizeValue = row("缓存大小上限", "cache_size", new View.OnClickListener() {
            @Override public void onClick(View v) { showCacheSizes(); }
        });
        cacheUsageValue = row("已用空间与清理", "cache_usage", new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearCache(); }
        });
        TextView cacheNote = text("可选择车机存储、TF 卡或 U 盘。切换后新下载使用新目录，旧缓存保留；拔出外置存储后，新请求回退到内部缓存。达到上限时先清理久未播放的歌曲，正在使用的歌曲会保留。", 12, R.color.text_secondary);
        cacheNote.setPadding(dp(4), dp(6), dp(4), dp(8));
        content.addView(cacheNote);
        section("应用");
        row("账户管理与切换", "accounts", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, LoginActivity.class).putExtra("manage_accounts", true));
            }
        }).setText("添加、删除或免密切换已保存的账户");
        row("问题反馈与运行日志", "feedback", new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, FeedbackActivity.class));
            }
        }).setText("生成加密证据并上传到开发者 AList");
        row("检查更新", "update", new View.OnClickListener() {
            @Override public void onClick(View v) { AppUpdater.check(SettingsActivity.this, true); }
        }).setText("当前版本 " + versionName());
        refreshValues();
        UiAppearance.apply(root);
        refreshCacheUsage(false);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(getResources().getColor(color));
        UiAppearance.textSize(view, size);
        return view;
    }

    private void section(String label) {
        TextView view = text(label, 13, R.color.primary);
        view.setPadding(dp(4), dp(12), 0, dp(8));
        content.addView(view);
    }

    private TextView row(String title, String tag, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(this);
        row.setTag(tag);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(68));
        row.setBackgroundResource(R.drawable.bg_list_item);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(action);
        row.addView(text(title, 16, R.color.text_primary));
        TextView value = text("", 13, R.color.text_secondary);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(4);
        row.addView(value, params);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(4);
        content.addView(row, rowParams);
        return value;
    }

    private void refreshValues() {
        if (navigationValue == null) return;
        boolean[] visible = preferences.visibleNavigation();
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < visible.length; i++) if (visible[i]) {
            if (labels.length() > 0) labels.append("、");
            labels.append(UiPreferences.NAV_LABELS[i]);
        }
        navigationValue.setText(labels.toString());
        fontValue.setText(UiPreferences.FONT_LABELS[preferences.fontMode()] + " · 即时预览文字大小");
        cacheSizeValue.setText(SongCache.formatBytes(songCache.limitMb() * 1024L * 1024L));
        File actual = CacheDirectories.resolve(this);
        String selected = CacheDirectories.selected(this);
        cacheDirectoryValue.setText(actual.getAbsolutePath() + (!selected.isEmpty() && !selected.equals(actual.getAbsolutePath())
                ? "\n外置目录暂不可用，已回退内部缓存" : ""));
        autoPlayValue.setText(playbackPreferences.autoPlayOnLaunch() ? "已开启 · 继续上次的歌曲" : "已关闭");
        navigationAudioValue.setText(playbackPreferences.pauseForNavigation() ? "播报时暂停音乐 · 默认，适合不支持混音的车机" : "播报时降低音乐音量 · 混音");
        themeValue.setText(ThemeSettings.LABELS[ThemeSettings.mode(this)]);
        fadeValue.setText(playbackPreferences.fadeMs() == 0 ? "关闭" : playbackPreferences.fadeMs() + " ms");
        preloadValue.setText(playbackPreferences.preloadCount() == 0 ? "关闭" : "后续 " + playbackPreferences.preloadCount() + " 首歌曲");
        audioValue.setText(playback == null ? "正在连接播放服务…" : AudioCompatibility.label(playback.getAudioMode()));
        bitrateValue.setText(playback == null ? "正在连接播放服务…" : playback.getMp3BitrateKbps() + " kbps · 仅用于 MP3 转码");
        ((View) audioValue.getParent()).setEnabled(playback != null);
        ((View) bitrateValue.getParent()).setEnabled(playback != null);
    }

    private void showNavigation() {
        final boolean[] visible = preferences.visibleNavigation();
        final AlertDialog dialog = AudioSettingsDialogs.show(new AlertDialog.Builder(this)
                .setTitle("侧边栏栏目")
                .setMultiChoiceItems(UiPreferences.NAV_LABELS, visible, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean checked) { visible[which] = checked; }
                }).setPositiveButton("保存", null).setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        boolean[] defaults = new boolean[UiPreferences.NAV_KEYS.length];
                        java.util.Arrays.fill(defaults, true);
                        preferences.saveNavigation(defaults);
                        refreshValues();
                    }
                }));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!preferences.saveNavigation(visible)) {
                    Toast.makeText(SettingsActivity.this, "请至少保留一个栏目", Toast.LENGTH_SHORT).show();
                    return;
                }
                refreshValues();
                dialog.dismiss();
            }
        });
    }

    private void showFonts() {
        AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("字体大小")
                .setSingleChoiceItems(UiPreferences.FONT_LABELS, preferences.fontMode(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        preferences.setFontMode(which);
                        UiAppearance.apply(root);
                        refreshValues();
                        dialog.dismiss();
                    }
                }).setNegativeButton("取消", null));
    }

    private void showNavigationAudio() {
        AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("导航播报处理")
                .setSingleChoiceItems(new String[] {"播报时暂停音乐（默认）", "播报时降低音乐音量"},
                        playbackPreferences.pauseForNavigation() ? 0 : 1, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                playbackPreferences.setPauseForNavigation(which == 0);
                                if (playback != null) playback.refreshPlaybackOptions();
                                refreshValues(); dialog.dismiss();
                            }
                        }).setNegativeButton("取消", null));
    }

    private void showCacheDirectories() {
        cacheWorker.execute(new Runnable() {
            @Override public void run() {
                final List<File> candidates = CacheDirectories.candidates(SettingsActivity.this);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed || isFinishing()) return;
                        String[] labels = new String[candidates.size() + 2];
                        labels[0] = "内部缓存（默认）";
                        for (int i = 0; i < candidates.size(); i++) labels[i + 1] = "存储目录\n" + candidates.get(i).getAbsolutePath();
                        labels[labels.length - 1] = "自定义 TF 卡 / U 盘路径…";
                        AudioSettingsDialogs.show(new AlertDialog.Builder(SettingsActivity.this).setTitle("选择缓存存储位置")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface dialog, int which) {
                                        if (which == labels.length - 1) showCustomCacheDirectory();
                                        else selectCacheDirectory(which == 0 ? null : candidates.get(which - 1));
                                    }
                                }).setNegativeButton("取消", null));
                    }
                });
            }
        });
    }

    private void showCustomCacheDirectory() {
        final EditText input = new EditText(this);
        input.setSingleLine(true); input.setTag("cache_custom_directory");
        input.setHint("例如 /storage/usb1 或 /mnt/extsd");
        final AlertDialog dialog = AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("自定义存储路径")
                .setMessage("输入 TF 卡或 U 盘上已挂载的绝对路径，程序会创建独立缓存子目录。Android 新版本请选择列表中的应用专用目录。")
                .setView(input).setPositiveButton("使用此目录", null).setNegativeButton("取消", null));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                File parent = new File(input.getText().toString().trim());
                if (!parent.isAbsolute() || !parent.isDirectory()) { input.setError("请输入已挂载且存在的绝对目录路径"); return; }
                dialog.dismiss(); selectCacheDirectory(parent);
            }
        });
    }

    private void selectCacheDirectory(final File parent) {
        cacheDirectoryValue.setText("正在检查目录是否可写…");
        cacheWorker.execute(new Runnable() {
            @Override public void run() {
                String problem = null;
                try { CacheDirectories.select(SettingsActivity.this, parent); }
                catch (Exception error) { problem = "缓存目录不可写：" + error.getMessage(); }
                final String failure = problem;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        // App-specific directories usually need no permission. Ask only after a failed write.
                        if (failure != null && parent != null && android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 28
                                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            pendingCacheParent = parent;
                            requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 408);
                            refreshValues(); return;
                        }
                        songCache = new SongCache(SettingsActivity.this);
                        if (playback != null) playback.refreshPlaybackOptions();
                        refreshValues(); refreshCacheUsage(false);
                        Toast.makeText(SettingsActivity.this, failure == null ? "缓存目录已保存，新下载将使用此目录" : failure, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 408) {
            File parent = pendingCacheParent; pendingCacheParent = null;
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) selectCacheDirectory(parent);
            else Toast.makeText(this, "未获得存储权限，请选择应用专用目录或内部缓存", Toast.LENGTH_LONG).show();
        }
    }

    private void showCacheSizes() {
        final int[] sizes = {64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384};
        String[] labels = new String[sizes.length];
        int checked = -1;
        for (int i = 0; i < sizes.length; i++) {
            labels[i] = SongCache.formatBytes(sizes[i] * 1024L * 1024L)
                    + (sizes[i] == SongCache.DEFAULT_LIMIT_MB ? " · 默认" : "");
            if (sizes[i] == songCache.limitMb()) checked = i;
        }
        AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("歌曲缓存上限")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        songCache.setLimitMb(sizes[which]);
                        if (playback != null) playback.refreshPlaybackOptions();
                        refreshValues();
                        refreshCacheUsage(true);
                        dialog.dismiss();
                    }
                }).setNeutralButton("自定义", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { showCustomCacheSize(); }
                }).setNegativeButton("取消", null));
    }

    private void showCustomCacheSize() {
        final EditText input = new EditText(this);
        input.setTag("cache_custom_mb");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(songCache.limitMb()));
        input.selectAll();
        input.setHint("64–65536 MB");
        final AlertDialog dialog = AudioSettingsDialogs.show(new AlertDialog.Builder(this)
                .setTitle("自定义缓存大小（MB）").setMessage("范围 64–65536 MB，1024 MB = 1 GB。只设置上限，不会预先占满存储空间。")
                .setView(input).setPositiveButton("保存", null).setNegativeButton("取消", null));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int value = -1;
                try { value = Integer.parseInt(input.getText().toString().trim()); } catch (NumberFormatException ignored) { }
                if (!songCache.setLimitMb(value)) {
                    input.setError("请输入 64–65536 之间的整数");
                    return;
                }
                if (playback != null) playback.refreshPlaybackOptions();
                refreshValues();
                refreshCacheUsage(true);
                dialog.dismiss();
            }
        });
    }

    private void confirmClearCache() {
        AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("清理歌曲缓存")
                .setMessage("清理后，再次播放这些歌曲需要重新加载。正在播放或下载的歌曲会保留；歌单、收藏和播放记录不受影响。")
                .setPositiveButton("清理", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { refreshCacheUsage(false, true); }
                }).setNegativeButton("取消", null));
    }

    private void showFadeOptions() {
        final int[] durations = {0, 250, 500, 1000, 1500, 2000};
        String[] labels = {"关闭", "250 ms", "500 ms · 默认", "1 秒", "1.5 秒", "2 秒"};
        int checked = -1;
        for (int i = 0; i < durations.length; i++) if (durations[i] == playbackPreferences.fadeMs()) checked = i;
        AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("切歌淡入淡出")
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playbackPreferences.setFadeMs(durations[which]);
                        if (playback != null) playback.refreshPlaybackOptions();
                        refreshValues(); dialog.dismiss();
                    }
                }).setNegativeButton("取消", null));
    }

    private void showPreloadOptions() {
        String[] labels = {"关闭", "后续 1 首", "后续 2 首 · 默认", "后续 3 首"};
        AudioSettingsDialogs.show(new AlertDialog.Builder(this).setTitle("列表歌曲预加载")
                .setSingleChoiceItems(labels, playbackPreferences.preloadCount(), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        playbackPreferences.setPreloadCount(which);
                        if (playback != null) playback.refreshPlaybackOptions();
                        refreshValues(); dialog.dismiss();
                    }
                }).setNegativeButton("取消", null));
    }

    private void refreshCacheUsage(boolean trim) { refreshCacheUsage(trim, false); }

    private void refreshCacheUsage(final boolean trim, final boolean clear) {
        if (destroyed) return;
        final int generation = ++cacheGeneration;
        final SongCache cacheSnapshot = new SongCache(this);
        cacheUsageValue.setText(clear ? "正在清理…" : "正在读取缓存大小…");
        cacheWorker.execute(new Runnable() {
            @Override public void run() {
                final SongCache.Usage usage = clear ? cacheSnapshot.clearUnused()
                        : trim ? cacheSnapshot.trimToLimit() : cacheSnapshot.usage();
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed || generation != cacheGeneration) return;
                        cacheUsageValue.setText("已用 " + SongCache.formatBytes(usage.bytes) + " · " + usage.songs
                                + " 首完整歌曲\n剩余存储 " + SongCache.formatBytes(usage.availableBytes) + " · 点击清理");
                        if (clear) Toast.makeText(SettingsActivity.this, usage.bytes == 0L ? "歌曲缓存已清理" : "已清理，保留正在使用的歌曲", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        serviceBinding = bindService(new Intent(this, PlaybackService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (serviceBinding) unbindService(connection);
        serviceBinding = false;
        playback = null;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshValues();
        refreshCacheUsage(false);
        AppUpdater.onResume(this);
    }
    @Override protected void onPause() { AppUpdater.onPause(this); super.onPause(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AppUpdater.resumePendingInstall(this, requestCode);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return MediaKeyRouter.dispatch(this, event) || super.dispatchKeyEvent(event);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        cacheGeneration++;
        handler.removeCallbacksAndMessages(null);
        cacheWorker.shutdownNow();
        super.onDestroy();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return ""; }
    }
}
