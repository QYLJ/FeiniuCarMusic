package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.data.FnConnectResolver;
import com.feiniu.carmusic.data.SessionStore;
import com.feiniu.carmusic.data.AccountStore;
import com.feiniu.carmusic.player.PlaybackService;
import com.feiniu.carmusic.diagnostics.AppLog;
import com.feiniu.carmusic.update.AppUpdater;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginActivity extends ThemeActivity {
    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private TextView statusText;
    private Button loginButton;
    private FnApiClient api;
    private AccountStore accounts;
    private CheckBox remember;
    private LinearLayout savedAccounts;
    private boolean busy;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        api = FnApiClient.get(this);
        accounts = new AccountStore(this);
        SessionStore session = api.session();

        serverInput = (EditText) findViewById(R.id.server_input);
        usernameInput = (EditText) findViewById(R.id.username_input);
        passwordInput = (EditText) findViewById(R.id.password_input);
        statusText = (TextView) findViewById(R.id.status_text);
        loginButton = (Button) findViewById(R.id.login_button);
        remember = (CheckBox) findViewById(R.id.remember_account);
        savedAccounts = (LinearLayout) findViewById(R.id.saved_accounts);

        final View root = findViewById(R.id.login_root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                    int oldL, int oldT, int oldR, int oldB) {
                if (r - l == oldR - oldL && b - t == oldB - oldT) return;
                float density = getResources().getDisplayMetrics().density;
                boolean compact = (b - t) / density < 400 || (r - l) / density < 760;
                int inset = Math.round((compact ? 12 : 40) * density);
                root.setPadding(inset, inset, inset, inset);
                UiAppearance.textSize((TextView) findViewById(R.id.login_heading), compact ? 24 : 34);
                int formInset = Math.round((compact ? 12 : 26) * density);
                findViewById(R.id.login_form).setPadding(formInset, formInset, formInset, formInset);
                EditText[] fields = {serverInput, usernameInput, passwordInput};
                for (int i = 0; i < fields.length; i++) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fields[i].getLayoutParams();
                    params.height = Math.round(Math.max(compact ? 36 : 40,
                            32 * UiAppearance.textScale(LoginActivity.this)) * density);
                    params.topMargin = Math.round((i == 0 ? 0 : compact ? 6 : 8) * density);
                    fields[i].setLayoutParams(params);
                }
                LinearLayout.LayoutParams heading = (LinearLayout.LayoutParams) findViewById(R.id.login_form_heading).getLayoutParams();
                heading.bottomMargin = Math.round((compact ? 8 : 12) * density);
                findViewById(R.id.login_form_heading).setLayoutParams(heading);
                UiAppearance.textSize(statusText, compact ? 12 : 13);
                statusText.setMinHeight(Math.round((compact ? 18 : 22) * density));
            }
        });

        serverInput.setText(session.getLoginAddress());
        usernameInput.setText(session.getUsername());
        showStatus("支持 IP、域名和 FN ID（FN Connect）", false);
        loginButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptLogin();
            }
        });
        findViewById(R.id.login_update_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                AppUpdater.check(LoginActivity.this, true);
            }
        });
        findViewById(R.id.login_feedback_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                startActivity(new Intent(LoginActivity.this, FeedbackActivity.class));
            }
        });

        if (session.hasSession() && !getIntent().getBooleanExtra("manage_accounts", false)) openLibrary();
        else refreshAccounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiAppearance.apply(findViewById(R.id.login_root));
        if (!isFinishing()) AppUpdater.onResume(this);
    }

    @Override
    protected void onPause() {
        AppUpdater.onPause(this);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AppUpdater.resumePendingInstall(this, requestCode);
    }

    private void attemptLogin() {
        final String server = serverInput.getText().toString().trim();
        final String username = usernameInput.getText().toString().trim();
        final String password = passwordInput.getText().toString();
        if (server.length() == 0 || username.length() == 0 || password.length() == 0) {
            showStatus("请完整填写 NAS 地址或 FN ID、用户名和密码", true);
            return;
        }

        login(server, username, password, remember.isChecked());
    }

    private void login(final String server, final String username, final String password, final boolean save) {
        if (busy || destroyed) return;
        busy = true;

        hideKeyboard();
        AppLog.info("login.started", FnConnectResolver.looksLikeFnIdInput(server) ? "route=fn_connect" : "route=direct");
        loginButton.setEnabled(false);
        showStatus(FnConnectResolver.looksLikeFnIdInput(server)
                ? "正在通过 FN Connect 查找 NAS…" : "正在连接飞牛音乐服务…", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final FnApiClient.LoginResult result = api.authenticate(server, username, password);
                    if (destroyed) return;
                    boolean stored = true;
                    if (save) try { accounts.save(result.loginAddress, result.username, password); }
                    catch (Exception unavailable) { stored = false; }
                    final boolean saved = stored;
                    AppLog.info("login.succeeded", "music session established");
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            PlaybackService.resetForAccountSwitch(LoginActivity.this);
                            api.session().saveLogin(result.baseUrl, result.token, result.username, result.loginAddress);
                            busy = false;
                            passwordInput.setText("");
                            loginButton.setEnabled(true);
                            showStatus("连接成功", false);
                            if (!saved) Toast.makeText(LoginActivity.this, "登录成功，但本机账户加密保存暂不可用", Toast.LENGTH_LONG).show();
                            openLibrary();
                        }
                    });
                } catch (final Exception error) {
                    AppLog.error("login.failed", error);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            busy = false;
                            loginButton.setEnabled(true);
                            showStatus(error.getMessage() == null ? "登录失败" : error.getMessage(), true);
                        }
                    });
                }
            }
        });
    }

    private void refreshAccounts() {
        if (destroyed) return;
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final java.util.List<AccountStore.Account> saved = accounts.list();
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            savedAccounts.removeAllViews();
                            TextView title = new TextView(LoginActivity.this);
                            title.setText(saved.isEmpty() ? "登录后可保存账户，下次点击即可切换" : "已保存账户 · 点击快速切换");
                            title.setTextColor(getResources().getColor(com.feiniu.carmusic.R.color.text_secondary));
                            UiAppearance.textSize(title, 13);
                            savedAccounts.addView(title);
                            for (final AccountStore.Account account : saved) addAccountRow(account);
                            Button add = new Button(LoginActivity.this);
                            add.setText("添加账户"); add.setTag("add_account");
                            add.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View view) {
                                    if (busy) return;
                                    usernameInput.setText(""); passwordInput.setText("");
                                    remember.setChecked(true); usernameInput.requestFocus();
                                    showStatus("填写账户并登录，成功后保存到本机", false);
                                }
                            });
                            savedAccounts.addView(add);
                            UiAppearance.apply(savedAccounts);
                        }
                    });
                } catch (Exception unavailable) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed) return;
                            savedAccounts.removeAllViews();
                            Button reset = new Button(LoginActivity.this);
                            reset.setText("已保存账户不可用 · 点击清除后重新添加");
                            reset.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View view) {
                                    AudioSettingsDialogs.show(new AlertDialog.Builder(LoginActivity.this).setTitle("清除已保存账户")
                                            .setMessage("删除本机保存的账号密码，之后需要重新添加。")
                                            .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface dialog, int which) { accounts.clear(); refreshAccounts(); }
                                            }).setNegativeButton("取消", null));
                                }
                            });
                            savedAccounts.addView(reset);
                        }
                    });
                }
            }
        });
    }

    private void addAccountRow(final AccountStore.Account account) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button select = new Button(this);
        select.setText(account.username + "\n" + account.server);
        select.setTag("account_" + account.id);
        select.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (busy) return;
                if (api.session().hasSession() && account.username.equals(api.session().getUsername())
                        && account.server.equals(api.session().getLoginAddress())) { openLibrary(); return; }
                login(account.server, account.username, account.password(), true);
            }
        });
        row.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
        Button delete = new Button(this); delete.setText("删除"); delete.setContentDescription("删除保存账户 " + account.username);
        delete.setTag("delete_account_" + account.id);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (busy) return;
                AudioSettingsDialogs.show(new AlertDialog.Builder(LoginActivity.this).setTitle("删除保存账户")
                        .setMessage("删除本机保存的 " + account.username + " 的账号密码，不影响 NAS 上的账户。")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                if (destroyed || busy) return;
                                executor.execute(new Runnable() {
                                    @Override public void run() {
                                        try { accounts.delete(account.id); }
                                        catch (Exception failed) {
                                            main.post(new Runnable() {
                                                @Override public void run() {
                                                    if (!destroyed) showStatus("删除失败，请重试或清除已保存账户后重新添加", true);
                                                }
                                            });
                                            return;
                                        }
                                        main.post(new Runnable() { @Override public void run() { if (!destroyed) refreshAccounts(); } });
                                    }
                                });
                            }
                        }).setNegativeButton("取消", null));
            }
        });
        row.addView(delete, new LinearLayout.LayoutParams(-2, -2));
        savedAccounts.addView(row);
    }

    private void showStatus(String text, boolean error) {
        statusText.setText(text);
        statusText.setTextColor(error ? getResources().getColor(R.color.error)
                : getResources().getColor(R.color.text_secondary));
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void openLibrary() {
        Intent intent = new Intent(this, LibraryActivity.class);
        boolean launcher = Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER);
        intent.putExtra(LibraryActivity.EXTRA_LAUNCH_PLAY, launcher && !getIntent().getBooleanExtra("manage_accounts", false));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
