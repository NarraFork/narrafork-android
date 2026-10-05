package com.termux.app.narrafork;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.NarraForkBootstrapBridge;
import com.termux.app.TermuxActivity;

/**
 * NarraFork 主界面（自定义菜单）。
 *
 * 状态区 + 端口配置 + [启动/停止] + [打开 NarraFork(WebView)] + [打开终端]。
 * 不嵌入终端视图；终端仅作为逃生通道跳转到 TermuxActivity。
 */
public final class NarraForkDashboardActivity extends AppCompatActivity {

    private TextView statusDot;
    private TextView statusText;
    private EditText portInput;
    private Button btnToggle;
    private Button btnOpen;
    private Button btnTerminal;
    private Button btnBackup;
    private ScrollView logScroll;
    private TextView logView;

    /**
     * 本次进入应用是否已经自动跳过 WebView。
     *
     * 用来避免「从 WebView 按返回回到菜单 → 又被立刻弹回 WebView」这种关不掉的循环：
     * 自动跳转只在一次 Activity 生命周期内做一次，之后由用户主动点按钮。
     */
    private boolean autoOpenDone = false;

    /**
     * 用户是否已主动跳到别的界面（备份/恢复、终端…）。
     *
     * 自动启动要等服务就绪（可能几十秒），这期间用户完全可能自己点进「备份/恢复」。
     * 如果那时仍然无条件跳 WebView，就会把用户正在操作的界面抢掉。
     */
    private boolean userNavigatedAway = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable healthPoller = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_narrafork_dashboard);

        statusDot = findViewById(R.id.nf_status_dot);
        statusText = findViewById(R.id.nf_status_text);
        portInput = findViewById(R.id.nf_port_input);
        btnToggle = findViewById(R.id.nf_btn_toggle);
        btnOpen = findViewById(R.id.nf_btn_open);
        btnTerminal = findViewById(R.id.nf_btn_terminal);
        btnBackup = findViewById(R.id.nf_btn_backup);
        logScroll = findViewById(R.id.nf_log_scroll);
        logView = findViewById(R.id.nf_log);

        portInput.setText(String.valueOf(NarraForkController.getPort(this)));
        portInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                try {
                    int p = Integer.parseInt(s.toString().trim());
                    if (p > 0 && p < 65536) NarraForkController.setPort(NarraForkDashboardActivity.this, p);
                } catch (NumberFormatException ignored) {}
            }
        });

        btnToggle.setOnClickListener(v -> onToggle());
        btnOpen.setOnClickListener(v -> openWebView());
        btnTerminal.setOnClickListener(v -> {
            userNavigatedAway = true;
            startActivity(new Intent(this, TermuxActivity.class));
        });
        btnBackup.setOnClickListener(v -> {
            userNavigatedAway = true;
            startActivity(new Intent(this, NarraForkBackupActivity.class));
        });

        // 区分两种进入方式：
        //  - 点桌面图标（LAUNCHER）：这是"我要用 NarraFork"，应当自动启动并进 WebView
        //  - 长按图标选「控制面板」：这是"我要排查/改配置"，就该停在这个面板上
        // 判据是 intent 的 category：LAUNCHER 只在前者出现。
        Intent launchIntent = getIntent();
        boolean fromLauncher = launchIntent != null
            && launchIntent.hasCategory(Intent.CATEGORY_LAUNCHER);
        if (!fromLauncher) {
            userNavigatedAway = true; // 抑制自动跳转，留在面板
            appendLog("已进入控制面板（不会自动跳转）");
        }

        // 首启：先确保 Termux bootstrap 已解包，再做 NarraFork 自己的安装。
        //
        // 顺序不能颠倒：容器导入要用 $PREFIX/bin/bash + proot-distro，而这些文件由
        // bootstrap 解包产生。原版 Termux 只在 TermuxActivity 里触发解包，而这里
        // Dashboard 才是 LAUNCHER，所以必须自己触发一次，否则安装器会因为
        // 「可执行文件不存在」而失败（表现为 exitCode 为 null、无任何输出）。
        ensureBootstrapThenInstall();
    }

    /** 先解包 bootstrap（已解包则立即继续），再按需跑 NarraFork 安装流程。 */
    private void ensureBootstrapThenInstall() {
        btnToggle.setEnabled(false);
        appendLog("检查运行环境…");
        try {
            NarraForkBootstrapBridge.setupBootstrapIfNeeded(this, () -> runOnUiThread(() -> {
                appendLog("✓ 运行环境就绪");
                btnToggle.setEnabled(true);
                if (!NarraForkInstaller.isInstalled(this)) {
                    startInstall();
                } else {
                    // 环境已就绪：直接自动启动并在就绪后跳进 WebView。
                    // 打开一个专门为 NarraFork 做的应用却还要再点一次「启动」，是多余的。
                    autoStartAndOpen();
                }
            }));
        } catch (Exception e) {
            appendLog("✗ 环境准备失败：" + e.getMessage());
            btnToggle.setEnabled(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(healthPoller);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(healthPoller);
    }

    /**
     * 自动启动 NarraFork，就绪后自动跳进 WebView。
     *
     * 设计取舍：
     *  - 只在「本次进入应用还没自动跳过」时跳一次（autoOpenDone），否则用户从 WebView
     *    按返回键回到菜单会被立刻又弹进去，等于关不掉。
     *  - 已在运行就直接跳，不重复启动。
     *  - 启动失败/超时不强跳，把状态留在菜单上让用户能看到日志。
     */
    private void autoStartAndOpen() {
        if (autoOpenDone) return;
        autoOpenDone = true;
        // 注意：这里不能重置 userNavigatedAway。
        // 从长按快捷方式进入控制面板时，onCreate 已把它置为 true 以抑制自动跳转；
        // 在这里清零会把那个意图擦掉，用户又被弹进 WebView。
        // 服务仍然照常自动启动，只是不跳转。

        new Thread(() -> {
            int port = NarraForkController.getPort(this);
            boolean already = NarraForkController.isRunning(port, 1000);
            if (!already) {
                runOnUiThread(() -> appendLog("自动启动 NarraFork（端口 " + port + "）…"));
                NarraForkController.start(this);
            } else {
                runOnUiThread(() -> appendLog("NarraFork 已在运行"));
            }

            // 等待就绪。首启要生成 CA + 证书 + 建库，给足时间；每 2s 探一次。
            int waitedMs = 0;
            final int stepMs = 2000;
            final int maxMs = 180_000;
            boolean up = already;
            while (!up && waitedMs < maxMs) {
                try {
                    Thread.sleep(stepMs);
                } catch (InterruptedException e) {
                    return;
                }
                waitedMs += stepMs;
                up = NarraForkController.isRunning(port, 1500);
            }

            final boolean ready = up;
            final int secs = waitedMs / 1000;
            runOnUiThread(() -> {
                refreshStatus();
                if (ready) {
                    // 等待期间用户可能已经自己点进了别的界面（例如「备份/恢复」或终端）。
                    // 这时把他弹进 WebView 是在抢焦点、打断操作，所以只记录不跳转。
                    if (userNavigatedAway) {
                        appendLog("✓ 已就绪（" + secs + "s）");
                        return;
                    }
                    appendLog("✓ 已就绪（" + secs + "s），正在打开界面…");
                    openWebView();
                } else {
                    appendLog("✗ 等待超时（" + secs + "s），未自动打开。");
                    appendLog("可点「启动」重试，或进「终端」查看原因。");
                }
            });
        }, "nf-autostart").start();
    }

    /**
     * 启动 / 停止切换。
     *
     * isRunning() 必须在后台线程调用：它是同步 HTTP 请求，在 UI 线程会抛
     * NetworkOnMainThreadException，而 probe() 里的 catch 会把它当成"服务没在跑"
     * 一并吞掉 —— 于是按钮永远走启动分支，表现为「点停止却又启动了一次」。
     * 这正是之前的真实 bug，所以这里不能图省事同步判定。
     */
    private void onToggle() {
        btnToggle.setEnabled(false);
        int port = NarraForkController.getPort(this);
        new Thread(() -> {
            boolean running = NarraForkController.isRunning(port, 1200);
            runOnUiThread(() -> {
                btnToggle.setEnabled(true);
                if (running) {
                    appendLog("停止 NarraFork…");
                    NarraForkController.stop(this);
                    // 停止需要一点时间（要杀 proot 里的进程），多刷几次状态。
                    handler.postDelayed(this::refreshStatus, 2000);
                    handler.postDelayed(this::refreshStatus, 5000);
                } else {
                    appendLog("启动 NarraFork（端口 " + port + "）…");
                    NarraForkController.start(this);
                    handler.postDelayed(this::refreshStatus, 3000);
                }
            });
        }, "nf-toggle").start();
    }

    private void openWebView() {
        Intent i = new Intent(this, NarraForkWebViewActivity.class);
        i.putExtra(NarraForkWebViewActivity.EXTRA_URL, NarraForkController.getUrl(this));
        startActivity(i);
    }

    private void refreshStatus() {
        int port = NarraForkController.getPort(this);
        new Thread(() -> {
            boolean running = NarraForkController.isRunning(port, 800);
            runOnUiThread(() -> {
                statusDot.setText(running ? "●" : "○");
                statusDot.setTextColor(running ? 0xFF3FB950 : 0xFF8B949E);
                statusText.setText(running
                    ? "运行中 · " + NarraForkController.getUrl(this)
                    : (NarraForkInstaller.isInstalled(this) ? "已停止" : "未安装（正在准备…）"));
                btnToggle.setText(running ? "停止" : "启动");
                btnOpen.setEnabled(running);
            });
        }, "nf-health").start();
    }

    private void startInstall() {
        btnToggle.setEnabled(false);
        appendLog("首次运行，正在安装 NarraFork 环境…");
        NarraForkInstaller.install(this, new NarraForkInstaller.Callback() {
            @Override
            public void onLog(String line) {
                runOnUiThread(() -> appendLog(line));
            }

            @Override
            public void onDone(boolean success, String error) {
                runOnUiThread(() -> {
                    btnToggle.setEnabled(true);
                    if (success) {
                        appendLog("✓ 安装完成");
                        // 首启装完直接自动起，不让用户再点一次。
                        autoStartAndOpen();
                    } else {
                        appendLog("✗ 安装失败：" + error);
                    }
                    refreshStatus();
                });
            }
        });
    }

    private void appendLog(String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
