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

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.data.FnConnectResolver;
import com.feiniu.carmusic.data.SessionStore;
import com.feiniu.carmusic.update.AppUpdater;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoginActivity extends Activity {
    private EditText serverInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private TextView statusText;
    private Button loginButton;
    private FnApiClient api;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        api = FnApiClient.get(this);
        SessionStore session = api.session();

        serverInput = (EditText) findViewById(R.id.server_input);
        usernameInput = (EditText) findViewById(R.id.username_input);
        passwordInput = (EditText) findViewById(R.id.password_input);
        statusText = (TextView) findViewById(R.id.status_text);
        loginButton = (Button) findViewById(R.id.login_button);

        final View root = findViewById(R.id.login_root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                    int oldL, int oldT, int oldR, int oldB) {
                if (r - l == oldR - oldL && b - t == oldB - oldT) return;
                float density = getResources().getDisplayMetrics().density;
                boolean compact = (b - t) / density < 400 || (r - l) / density < 760;
                int inset = Math.round((compact ? 12 : 40) * density);
                root.setPadding(inset, inset, inset, inset);
                ((TextView) findViewById(R.id.login_heading)).setTextSize(compact ? 24 : 34);
                int formInset = Math.round((compact ? 12 : 26) * density);
                findViewById(R.id.login_form).setPadding(formInset, formInset, formInset, formInset);
                EditText[] fields = {serverInput, usernameInput, passwordInput};
                for (int i = 0; i < fields.length; i++) {
                    LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fields[i].getLayoutParams();
                    params.height = Math.round((compact ? 36 : 40) * density);
                    params.topMargin = Math.round((i == 0 ? 0 : compact ? 6 : 8) * density);
                    fields[i].setLayoutParams(params);
                }
                LinearLayout.LayoutParams heading = (LinearLayout.LayoutParams) findViewById(R.id.login_form_heading).getLayoutParams();
                heading.bottomMargin = Math.round((compact ? 8 : 12) * density);
                findViewById(R.id.login_form_heading).setLayoutParams(heading);
                statusText.setTextSize(compact ? 11 : 13);
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

        if (session.hasSession()) openLibrary();
    }

    @Override
    protected void onResume() {
        super.onResume();
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

        hideKeyboard();
        loginButton.setEnabled(false);
        showStatus(FnConnectResolver.looksLikeFnIdInput(server)
                ? "正在通过 FN Connect 查找 NAS…" : "正在连接飞牛音乐服务…", false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    api.login(server, username, password);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            passwordInput.setText("");
                            loginButton.setEnabled(true);
                            showStatus("连接成功", false);
                            openLibrary();
                        }
                    });
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed) return;
                            loginButton.setEnabled(true);
                            showStatus(error.getMessage() == null ? "登录失败" : error.getMessage(), true);
                        }
                    });
                }
            }
        });
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
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        super.onDestroy();
    }
}
