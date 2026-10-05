package com.termux.app.narrafork;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.TermuxConstants;

import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * NarraFork 运行控制器。
 *
 * 职责：
 *  - 端口等简单配置的读写（SharedPreferences）
 *  - 通过 AppShell 在 Termux 环境后台执行启动/停止脚本（无需可见终端）
 *  - 轮询 127.0.0.1:<port>/api/health 判定运行状态
 *
 * 真正的服务进程由 proot-distro 容器内的 narrafork-start 拉起（见
 * NarraForkInstaller 生成的 start.sh）。
 */
public final class NarraForkController {

    private static final String LOG_TAG = "NarraForkController";

    private static final String PREFS = "narrafork_box";
    private static final String KEY_PORT = "port";
    /**
     * NarraFork 的生产默认端口。
     *
     * 必须是 7778，与 NarraFork 自身 `server/lib/settings/defaults.ts` 里的
     * `server.port` 一致。7779 是它**开发模式**下后端的端口（`bun run dev`），
     * 那样 7778 才能留给 vite 前端做代理 —— 别再照搬开发环境的数字。
     */
    public static final int DEFAULT_PORT = 7778;

    /** 容器名（proot-distro）。与 NarraForkInstaller 一致。 */
    public static final String CONTAINER_NAME = "narrafork";

    private NarraForkController() {}

    // ── 配置 ────────────────────────────────────────────────────────────────

    public static int getPort(@NonNull Context context) {
        return prefs(context).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setPort(@NonNull Context context, int port) {
        prefs(context).edit().putInt(KEY_PORT, port).apply();
    }

    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── 启停 ────────────────────────────────────────────────────────────────

    /**
     * 启动 NarraFork。调用 proot-distro 进入容器并运行 narrafork-start。
     * 后台执行（AppShell，isSynchronous=false），立即返回。
     */
    public static void start(@NonNull Context context) {
        int port = getPort(context);
        // start.sh 由 NarraForkInstaller 生成；不存在则先跑安装流程。
        String home = TermuxConstants.TERMUX_FILES_DIR_PATH + "/home";
        String startScript = home + "/.narrafork-box/start.sh";
        String cmd = "sh \"" + startScript + "\"";
        runBackground(context, cmd, "narrafork-start");
        Logger.logInfo(LOG_TAG, "Start requested on port " + port);
    }

    /**
     * 停止容器内的 NarraFork 服务。
     *
     * 先 SIGTERM 再 SIGKILL，不是为了限制匹配范围（pkill 只能作用于本 app UID 下的
     * 进程，杀不到设备上其他应用的同名进程），而是让 NarraFork 有机会做 SQLite WAL
     * checkpoint 后再退出；直接 KILL 有留下脏 WAL 的风险。
     */
    public static void stop(@NonNull Context context) {
        String inner =
            "pkill -TERM -f narrafork 2>/dev/null || true; "
            + "sleep 3; "
            + "pkill -KILL -f narrafork 2>/dev/null || true; "
            + "true";
        String cmd = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot-distro login "
            + CONTAINER_NAME + " -- sh -c \"" + inner + "\"";
        runBackground(context, cmd, "narrafork-stop");
        Logger.logInfo(LOG_TAG, "Stop requested");
    }

    private static void runBackground(@NonNull Context context, @NonNull String shellCommand,
                                      @NonNull String label) {
        // 用 bash -lc 让 .profile 生效（PATH 等），脚本自身幂等。
        ExecutionCommand cmd = new ExecutionCommand(-1,
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
            new String[]{"-lc", shellCommand},
            null,
            TermuxConstants.TERMUX_FILES_DIR_PATH + "/home",
            ExecutionCommand.Runner.APP_SHELL.getName(),
            false);
        cmd.commandLabel = label;
        cmd.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;
        // isSynchronous=false → 后台线程执行，不阻塞 UI。
        AppShell.execute(context.getApplicationContext(), cmd, null, new TermuxShellEnvironment(), null, false);
    }

    // ── 状态 ────────────────────────────────────────────────────────────────

    /**
     * 同步健康检查（须在后台线程调用，勿在 UI 线程）。返回 true 表示服务在线。
     *
     * 同时探测 https 与 http：NarraFork 可能启用了 TLS（本机 CA 签发），也可能是明文。
     * 探测 https 时用 NarraForkTrust 的 CA 做信任锚点，否则本地 CA 签发的证书会被
     * 默认 TrustManager 拒绝，状态灯就会在服务明明在跑时一直显示「已停止」。
     */
    public static boolean isRunning(int port, int timeoutMs) {
        if (probe("https://127.0.0.1:" + port + "/api/health", timeoutMs)) return true;
        return probe("http://127.0.0.1:" + port + "/api/health", timeoutMs);
    }

    private static boolean probe(@NonNull String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                SSLSocketFactory sf = NarraForkTrust.localCaSocketFactory();
                if (sf == null) return false; // CA 还没生成，https 探测无从校验
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(sf);
                // 证书 SAN 含 127.0.0.1/localhost，主机名应当能正常匹配；
                // 这里不放宽 hostname 校验，避免削弱前面做的 CA 固定。
            }
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            return code >= 200 && code < 500; // 服务在监听即视为在线（health 可能 401 等）
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 访问地址。CA 存在即认为服务以 HTTPS 提供（TLS 由 NarraFork 自身开关控制），
     * 否则回落明文。
     */
    @NonNull
    public static String getUrl(@NonNull Context context) {
        String scheme = NarraForkTrust.caAvailable() ? "https" : "http";
        return scheme + "://127.0.0.1:" + getPort(context);
    }
}
