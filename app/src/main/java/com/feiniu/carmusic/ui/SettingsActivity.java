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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.UiPreferences;
import com.feiniu.carmusic.player.AudioCompatibility;
import com.feiniu.carmusic.player.MediaKeyRouter;
import com.feiniu.carmusic.player.PlaybackService;
import com.feiniu.carmusic.update.AppUpdater;

/** Scrollable settings remain reachable on small, old car head units. */
public final class SettingsActivity extends Activity {
    private UiPreferences preferences;
    private LinearLayout root, content;
    private TextView navigationValue, fontValue, audioValue, bitrateValue;
    private PlaybackService playback;
    private boolean serviceBinding;
    private final Runnable audioChanged = new Runnable() {
        @Override public void run() { refreshValues(); }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).getService();
            refreshValues();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            refreshValues();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new UiPreferences(this);
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
        navigationValue = row("侧边栏栏目", "navigation", new View.OnClickListener() {
            @Override public void onClick(View v) { showNavigation(); }
        });
        fontValue = row("字体大小", "font", new View.OnClickListener() {
            @Override public void onClick(View v) { showFonts(); }
        });
        section("播放");
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
        section("应用");
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

    @Override protected void onResume() { super.onResume(); AppUpdater.onResume(this); }
    @Override protected void onPause() { AppUpdater.onPause(this); super.onPause(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AppUpdater.resumePendingInstall(this, requestCode);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return MediaKeyRouter.dispatch(this, event) || super.dispatchKeyEvent(event);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { return ""; }
    }
}
