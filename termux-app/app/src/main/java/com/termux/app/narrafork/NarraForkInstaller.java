package com.termux.app.narrafork;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * NarraFork 首启安装器。
 *
 * 把打进 assets 的 rootfs 包导入 proot-distro 容器，并生成启动脚本 start.sh。
 * 所有耗时操作都在后台线程；通过回调通知 UI。
 *
 * 首启流程：
 *   1) bootstrap 已由 TermuxInstaller 解包（Termux 原生机制，新 PREFIX）
 *   2) rootfs 从 assets 拷到 files 下（流式，避免整包进内存）
 *   3) proot-distro install <pkg> --name narrafork
 *   4) 写 start.sh（读 SharedPreferences 端口，注入 PORT）
 */
public final class NarraForkInstaller {

    private static final String LOG_TAG = "NarraForkInstaller";

    /** assets 里的 rootfs 包名。用 .bin 后缀防止 aapt 打包时解压 gzip（内容仍是 gzip 流）。 */
    public static final String ROOTFS_ASSET = "narrafork-rootfs-arm64.bin";
    /** 落盘到 files 下的文件名（恢复 .tar.gz，供 proot-distro 识别压缩格式）。 */
    private static final String ROOTFS_STAGED = "narrafork-rootfs-arm64.tar.gz";

    public interface Callback {
        void onLog(@NonNull String line);
        void onDone(boolean success, @Nullable String error);
    }

    private NarraForkInstaller() {}

    /** 容器是否已装好（rootfs 已导入）。 */
    public static boolean isInstalled(@NonNull Context context) {
        File marker = new File(context.getFilesDir(), ".narrafork-box/installed");
        return marker.exists();
    }

    /** 后台执行完整安装流程。 */
    public static void install(@NonNull Context context, @NonNull Callback callback) {
        new Thread(() -> {
            try {
                callback.onLog("准备 rootfs 包…");
                File pkg = stageRootfs(context, callback);

                callback.onLog("生成启动脚本…");
                writeStartScript(context);

                callback.onLog("导入 proot-distro 容器（约 1–2 分钟）…");
                boolean ok = importContainer(context, pkg, callback);

                if (ok) {
                    new File(context.getFilesDir(), ".narrafork-box").mkdirs();
                    new File(context.getFilesDir(), ".narrafork-box/installed").createNewFile();
                    callback.onDone(true, null);
                } else {
                    callback.onDone(false, "容器导入失败");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "install failed", e);
                callback.onDone(false, e.getMessage());
            }
        }, "narrafork-install").start();
    }

    /** 把 assets 里的 rootfs 流式拷到 files 下（不整包进内存）。 */
    @NonNull
    private static File stageRootfs(@NonNull Context context, @NonNull Callback callback) throws Exception {
        File outDir = new File(context.getFilesDir(), ".narrafork-box");
        outDir.mkdirs();
        File out = new File(outDir, ROOTFS_STAGED);
        if (out.exists() && out.length() > 10_000_000) {
            callback.onLog("rootfs 已就绪（复用缓存）");
            return out;
        }
        try (InputStream in = context.getAssets().open(ROOTFS_ASSET);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[256 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                total += n;
            }
            callback.onLog("rootfs 已就绪（" + (total / 1048576) + " MB）");
        }
        return out;
    }

    /** 生成 start.sh：读端口 → 进容器 → 跑 narrafork-start。 */
    private static void writeStartScript(@NonNull Context context) {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File dir = new File(home, ".narrafork-box");
        dir.mkdirs();
        File script = new File(dir, "start.sh");
        String content = "#!/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/bash\n"
            + "set -e\n"
            + "PORT=\"${PORT:-" + NarraForkController.DEFAULT_PORT + "}\"\n"
            + "export NARRAFORK_ANDROID=1 NARRAFORK_PROOT=1\n"
            + "exec " + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/proot-distro login "
            + NarraForkController.CONTAINER_NAME
            + " -- /usr/local/bin/narrafork-start\n";
        try (OutputStream os = new FileOutputStream(script)) {
            os.write(content.getBytes());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "writeStartScript failed", e);
        }
        script.setExecutable(true);
    }

    /**
     * proot-distro install。经 AppShell 同步跑（在后台线程内），取退出码判定。
     *
     * 两个必须显式处理的点（真机实测踩到）：
     *  - `proot-distro` 只是个调 python 的薄 wrapper。AppShell 的环境不等于交互式登录 shell，
     *    PATH 里可能没有 $PREFIX/bin，于是 wrapper 找不到 python 而失败。因此显式注入 PATH。
     *  - `proot-distro list` 在没有任何容器时的输出/退出码不保证稳定，用它 grep 判断"是否已安装"
     *    很脆弱。改为直接看容器 rootfs 目录是否存在。
     */
    private static boolean importContainer(@NonNull Context context, @NonNull File pkg,
                                           @NonNull Callback callback) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String bin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
        String bash = bin + "/bash";
        String name = NarraForkController.CONTAINER_NAME;

        // 先确认解释器真的存在。bootstrap 未解包时 AppShell 起不了进程，得到的是
        // exitCode=null、stdout/stderr 全空的失败 —— 这种"没有任何线索"的报错极难定位，
        // 所以在这里把它变成一句明确的说明。
        if (!new File(bash).canExecute()) {
            callback.onLog("运行环境尚未就绪：找不到可执行的 " + bash);
            callback.onLog("（Termux bootstrap 未解包完成，请重新打开应用或先进入一次「终端」）");
            return false;
        }
        if (!new File(bin + "/proot-distro").exists()) {
            callback.onLog("运行环境尚未就绪：找不到 proot-distro（bootstrap 可能不完整）");
            return false;
        }

        // proot-distro 5.x 的容器布局：$PREFIX/var/lib/proot-distro/containers/<name>/rootfs
        // 旧布局：installed-rootfs/<name>。两者都检查，任一存在即视为已装。
        String cmd = "export PATH=\"" + bin + ":$PATH\"; "
            + "export PREFIX=\"" + prefix + "\"; "
            + "newdir=\"" + prefix + "/var/lib/proot-distro/containers/" + name + "\"; "
            + "olddir=\"" + prefix + "/var/lib/proot-distro/installed-rootfs/" + name + "\"; "
            + "if [ -d \"$newdir\" ] || [ -d \"$olddir\" ]; then "
            + "  echo 'container-already-present'; exit 0; "
            + "fi; "
            + bin + "/proot-distro install '" + pkg.getAbsolutePath() + "' --name " + name;

        ExecutionCommand ec = new ExecutionCommand(-1, bash, new String[]{"-lc", cmd},
            null, TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.APP_SHELL.getName(), false);
        ec.commandLabel = "narrafork-import";
        AppShell shell = AppShell.execute(context.getApplicationContext(), ec, null,
            new TermuxShellEnvironment(), null, true);

        Integer exit = ec.resultData != null ? ec.resultData.exitCode : null;
        boolean ok = shell != null && exit != null && exit == 0;
        if (!ok) {
            // 之前只报 stderr，而失败信息常在 stdout 或体现为 exitCode，导致日志里是空的、
            // 真正原因被吞掉。这里把三者都带上。
            String out = ec.resultData != null && ec.resultData.stdout != null
                ? ec.resultData.stdout.toString().trim() : "";
            String err = ec.resultData != null && ec.resultData.stderr != null
                ? ec.resultData.stderr.toString().trim() : "";
            callback.onLog("导入失败 (exit=" + exit + ")");
            if (!err.isEmpty()) callback.onLog("stderr: " + tail(err));
            if (!out.isEmpty()) callback.onLog("stdout: " + tail(out));
            if (err.isEmpty() && out.isEmpty()) callback.onLog("(命令没有任何输出)");
        }
        return ok;
    }

    /** 只保留输出末尾若干字符，避免把整段解包日志灌进 UI。 */
    private static String tail(@NonNull String s) {
        int max = 1200;
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
