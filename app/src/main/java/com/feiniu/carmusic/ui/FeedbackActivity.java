package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.diagnostics.AppLog;
import com.feiniu.carmusic.feedback.AlistFeedbackClient;
import com.feiniu.carmusic.feedback.FeedbackRepository;
import com.feiniu.carmusic.player.MediaKeyRouter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-screen issue report flow, available before or after NAS login. */
public final class FeedbackActivity extends Activity {
    private static final String[] CATEGORIES = {"播放问题", "登录或连接", "界面显示", "更新问题", "建议", "其他"};
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Spinner category;
    private EditText message, contact;
    private CheckBox includeLogs;
    private TextView status;
    private Button submit, retry;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_screen_gradient);
        root.setTag("feedback_root");
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        PlayerControlButton back = new PlayerControlButton(this);
        back.configure(PlayerControlButton.BACK, "返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView heading = text("问题反馈与运行日志", 22, R.color.text_primary);
        heading.setTypeface(null, Typeface.BOLD);
        header.addView(heading);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView intro = text("请描述出现问题前后的操作。提交时会生成反馈编号，并把所选证据加密上传到开发者的 AList。", 13, R.color.text_secondary);
        intro.setPadding(dp(4), dp(4), dp(4), dp(10));
        content.addView(intro);

        category = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, CATEGORIES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);
        category.setBackgroundResource(R.drawable.bg_input);
        content.addView(category, fieldParams(44));

        message = new EditText(this);
        message.setHint("问题现象、复现步骤，以及你原本期望的结果（必填）");
        message.setTextColor(getResources().getColor(R.color.text_primary));
        message.setHintTextColor(getResources().getColor(R.color.text_secondary));
        message.setTextSize(15);
        message.setGravity(Gravity.TOP | Gravity.LEFT);
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setMinLines(4);
        message.setMaxLines(8);
        message.setPadding(dp(14), dp(12), dp(14), dp(12));
        message.setBackgroundResource(R.drawable.bg_input);
        content.addView(message, fieldParams(118));

        contact = new EditText(this);
        contact.setHint("联系方式（选填，例如微信/邮箱；不填也可反馈）");
        contact.setSingleLine(true);
        contact.setTextColor(getResources().getColor(R.color.text_primary));
        contact.setHintTextColor(getResources().getColor(R.color.text_secondary));
        contact.setBackgroundResource(R.drawable.bg_input);
        contact.setPadding(dp(14), 0, dp(14), 0);
        content.addView(contact, fieldParams(44));

        includeLogs = new CheckBox(this);
        includeLogs.setText("附带近期运行日志与最后一次崩溃记录（推荐）");
        includeLogs.setTextColor(getResources().getColor(R.color.text_primary));
        includeLogs.setChecked(true);
        includeLogs.setPadding(dp(4), dp(4), dp(4), dp(4));
        content.addView(includeLogs);

        TextView privacy = text("证据包含应用版本、车机型号、Android 版本、网络类型、内存/存储状态和应用自身日志；不主动采集密码、登录令牌、Cookie、Android ID 或音乐文件。上传包使用开发者公钥加密。", 12, R.color.text_secondary);
        privacy.setPadding(dp(4), 0, dp(4), dp(8));
        content.addView(privacy);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        submit = new Button(this);
        submit.setText("生成证据并上传");
        submit.setTextColor(getResources().getColor(R.color.text_primary));
        submit.setBackgroundResource(R.drawable.bg_accent_button);
        submit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitNew(); }
        });
        actions.addView(submit, new LinearLayout.LayoutParams(0, dp(44), 1));

        retry = new Button(this);
        retry.setText("重试未上传");
        retry.setTextColor(getResources().getColor(R.color.text_primary));
        retry.setBackgroundResource(R.drawable.bg_button);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        retryParams.leftMargin = dp(8);
        actions.addView(retry, retryParams);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { retryPending(); }
        });
        content.addView(actions);

        Button preview = new Button(this);
        preview.setText("查看将附带的日志摘要");
        preview.setTextColor(getResources().getColor(R.color.text_primary));
        preview.setBackgroundResource(R.drawable.bg_button);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(40));
        previewParams.topMargin = dp(8);
        content.addView(preview, previewParams);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLogPreview(); }
        });

        status = text("", 13, R.color.text_secondary);
        status.setPadding(dp(4), dp(10), dp(4), dp(4));
        content.addView(status);
        refreshStatus();
        UiAppearance.apply(root);
    }

    private void submitNew() {
        final String description = message.getText().toString().trim();
        if (description.length() < 5) {
            message.requestFocus();
            Toast.makeText(this, "请至少写下 5 个字的问题描述", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true, "正在生成并加密证据…");
        final String selectedCategory = String.valueOf(category.getSelectedItem());
        final String contactText = contact.getText().toString().trim();
        final boolean attachLogs = includeLogs.isChecked();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final FeedbackRepository.Pending pending = FeedbackRepository.prepare(
                            FeedbackActivity.this, selectedCategory, description, contactText, attachLogs);
                    postStatus("证据已保存，正在上传…");
                    final AlistFeedbackClient.UploadReceipt receipt = FeedbackRepository.upload(FeedbackActivity.this, pending);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            setBusy(false, "");
                            message.setText("");
                            showSuccess(receipt);
                            refreshStatus();
                        }
                    });
                } catch (final Exception error) {
                    showFailure(error);
                }
            }
        });
    }

    private void retryPending() {
        final FeedbackRepository.Pending pending = FeedbackRepository.oldestPending(this);
        if (pending == null) {
            Toast.makeText(this, "没有待上传的反馈", Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true, "正在重试 " + pending.id + "…");
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final AlistFeedbackClient.UploadReceipt receipt = FeedbackRepository.upload(FeedbackActivity.this, pending);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            setBusy(false, "");
                            showSuccess(receipt);
                            refreshStatus();
                        }
                    });
                } catch (final Exception error) {
                    showFailure(error);
                }
            }
        });
    }

    private void showFailure(final Exception error) {
        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                setBusy(false, "");
                refreshStatus();
                new AlertDialog.Builder(FeedbackActivity.this).setTitle("反馈暂未上传")
                        .setMessage(safeMessage(error) + "\n\n加密证据已保存在应用私有目录，可稍后点击“重试未上传”。")
                        .setPositiveButton("知道了", null).show();
            }
        });
    }

    private void showSuccess(final AlistFeedbackClient.UploadReceipt receipt) {
        String shortHash = receipt.sha256.substring(0, Math.min(16, receipt.sha256.length()));
        new AlertDialog.Builder(this).setTitle("反馈已上传")
                .setMessage("反馈编号：" + receipt.reportId + "\n证据摘要：" + shortHash
                        + "\n\n请保留反馈编号，便于开发者定位同一份证据。")
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制编号", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(ClipData.newPlainText("反馈编号", receipt.reportId));
                        Toast.makeText(FeedbackActivity.this, "反馈编号已复制", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showLogPreview() {
        String log = AppLog.recent(16 * 1024);
        if (log.length() == 0) log = "暂时还没有运行日志。";
        TextView view = text(log, 11, R.color.text_primary);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this).setTitle("日志摘要（已脱敏）").setView(scroll)
                .setPositiveButton("关闭", null).show();
    }

    private void refreshStatus() {
        int pending = FeedbackRepository.pendingCount(this);
        String pendingText = pending == 0 ? "没有待上传反馈" : "有 " + pending + " 份加密证据等待上传";
        status.setText(pendingText + "\n" + FeedbackRepository.lastReceipt(this)
                + "\n本机日志约 " + Math.max(1, AppLog.size() / 1024) + " KB");
        retry.setEnabled(pending > 0 && submit.isEnabled());
    }

    private void setBusy(boolean busy, String text) {
        submit.setEnabled(!busy);
        retry.setEnabled(!busy && FeedbackRepository.pendingCount(this) > 0);
        category.setEnabled(!busy);
        message.setEnabled(!busy);
        contact.setEnabled(!busy);
        includeLogs.setEnabled(!busy);
        if (text.length() > 0) status.setText(text);
    }

    private void postStatus(final String value) {
        main.post(new Runnable() {
            @Override public void run() { if (!destroyed) status.setText(value); }
        });
    }

    private String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().length() == 0 ? "反馈上传失败" : value;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(getResources().getColor(color));
        UiAppearance.textSize(view, size);
        return view;
    }

    private LinearLayout.LayoutParams fieldParams(int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.bottomMargin = dp(8);
        return params;
    }

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return MediaKeyRouter.dispatch(this, event) || super.dispatchKeyEvent(event);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
