package com.termux.app.narrafork;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * NarraFork Box 的导入/导出（备份/恢复）。
 *
 * 三种粒度：
 *   ROOTFS   — 整个 Debian 容器（proot-distro backup）
 *   HOME     — 容器内 /root（项目 + 配置 + 数据库）
 *   NF_DATA  — 仅 /root/.narrafork（NarraFork 自己的数据目录）
 *
 * 一致性（这是本类最重要的约束）：
 * NarraFork 的 SQLite 跑在 WAL 模式下，磁盘上是 narrafork.db + .db-wal + .db-shm 三个
 * 文件。服务正在写入时打包，这三者可能互相不同步，恢复后会得到损坏或丢数据的库。因此
 * **导出前一定先停服务并确认端口已关闭**，绝不提供"热备份"——那种包看起来成功，坏在
 * 恢复的时候，届时用户已经丢了原始数据。
 *
 * 导入同理需要停服务；且导入是破坏性的，所以强制先做一次 pre-import 备份。
 */
public final class NarraForkBackup {

    private static final String LOG_TAG = "NarraForkBackup";

    /** 默认导出目录。targetSdk 28 + requestLegacyExternalStorage 下可直写。 */
    public static final String EXPORT_DIR_NAME = "NarraFork";

    public enum Kind {
        ROOTFS("rootfs", "整个容器（rootfs）"),
        HOME("home", "用户目录 ~/"),
        NF_DATA("nfdata", "NarraFork 数据 ~/.narrafork");

        public final String slug;
        public final String label;

        Kind(String slug, String label) {
            this.slug = slug;
            this.label = label;
        }
    }

    public interface Callback {
        void onLog(@NonNull String line);
        /** @param outFile 导出成功时的产物；导入或失败时为 null */
        void onDone(boolean success, @Nullable File outFile, @Nullable String error);
    }

    private NarraForkBackup() {}

    // ── 路径 ────────────────────────────────────────────────────────────────

    @NonNull
    public static File exportDir() {
        File d = new File(Environment.getExternalStorageDirectory(), EXPORT_DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String bin() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    /**
     * 导出。会先停服务以保证数据库一致。
     *
     * @param restartAfter 导出完成后是否自动重启 NarraFork
     */
    public static void export(@NonNull Context context, @NonNull Kind kind,
                              boolean restartAfter, @NonNull Callback cb) {
        new Thread(() -> {
            try {
                if (!envReady(cb)) return;

                cb.onLog("停止 NarraFork 以获得一致的数据快照…");
                if (!stopAndWait(context, cb)) {
                    cb.onDone(false, null, "服务未能在预期时间内停止，已放弃导出");
                    return;
                }

                File out = new File(exportDir(),
                    "nfbox-" + kind.slug + "-" + timestamp() + ".tar.gz");
                cb.onLog("开始打包：" + kind.label);
                cb.onLog("目标：" + out.getAbsolutePath());

                boolean ok = runPack(context, kind, out, cb);

                if (ok && out.isFile() && out.length() > 0) {
                    cb.onLog("✓ 导出完成（" + human(out.length()) + "）");
                    if (restartAfter) restart(context, cb);
                    cb.onDone(true, out, null);
                } else {
                    if (out.exists() && out.length() == 0) out.delete();
                    if (restartAfter) restart(context, cb);
                    cb.onDone(false, null, "打包失败");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "export failed", e);
                cb.onDone(false, null, String.valueOf(e.getMessage()));
            }
        }, "nf-export").start();
    }

    private static boolean runPack(@NonNull Context context, @NonNull Kind kind,
                                   @NonNull File out, @NonNull Callback cb) {
        String name = NarraForkController.CONTAINER_NAME;
        String cmd;

        switch (kind) {
            case ROOTFS:
                // 复用 proot-distro 自己的 backup：它了解容器布局（含 l2s 链接处理）。
                // 显式用 gzip：proot-distro 的 zstd 需要 Python 3.14+（其 --help 明确写了），
                // 本容器的 python 版本不满足，选 zstd 会直接失败。
                cmd = "export PATH=\"" + bin() + ":$PATH\"; "
                    + bin() + "/proot-distro backup " + name
                    + " --compress gzip"
                    + " --output '" + out.getAbsolutePath() + "'";
                break;

            case HOME:
            case NF_DATA: {
                // 在容器内打包，路径语义（符号链接、权限）才是对的。
                // -C 到父目录再打相对路径，避免包里带绝对路径。
                String target = (kind == Kind.HOME) ? "root" : "root/.narrafork";
                // 不用 -h：rootfs 与 home 里有大量符号链接，解引用会让体积爆炸且语义错误。
                cmd = "export PATH=\"" + bin() + ":$PATH\"; "
                    + bin() + "/proot-distro login " + name
                    + " -- tar czf - -C / '" + target + "'"
                    + " > '" + out.getAbsolutePath() + "'";
                break;
            }

            default:
                return false;
        }

        Result r = sh(context, cmd, "nf-export-" + kind.slug);
        if (!r.ok) {
            cb.onLog("打包失败 (exit=" + r.exit + ")");
            if (!r.err.isEmpty()) cb.onLog("stderr: " + tail(r.err));
            if (!r.out.isEmpty()) cb.onLog("stdout: " + tail(r.out));
        }
        return r.ok;
    }

    // ── 导入 ────────────────────────────────────────────────────────────────

    /**
     * 导入（恢复）。破坏性操作：会先自动做一次 pre-import 备份。
     *
     * @param src 待导入的 tar.gz
     */
    public static void importFrom(@NonNull Context context, @NonNull Kind kind,
                                  @NonNull File src, boolean restartAfter,
                                  @NonNull Callback cb) {
        new Thread(() -> {
            try {
                if (!envReady(cb)) return;

                if (!src.isFile() || src.length() == 0) {
                    cb.onDone(false, null, "备份文件不存在或为空");
                    return;
                }

                cb.onLog("校验备份包结构…");
                if (!verifyArchive(context, kind, src, cb)) {
                    cb.onDone(false, null, "备份包结构不符合预期，已拒绝导入");
                    return;
                }

                cb.onLog("停止 NarraFork…");
                if (!stopAndWait(context, cb)) {
                    cb.onDone(false, null, "服务未能停止，已放弃导入");
                    return;
                }

                // 导入会覆盖现有数据。先留一份退路，否则包一旦有问题用户就无法回头。
                File safety = new File(exportDir(),
                    "nfbox-pre-import-" + kind.slug + "-" + timestamp() + ".tar.gz");
                cb.onLog("先备份当前数据到：" + safety.getName());
                if (runPack(context, kind, safety, cb)) {
                    cb.onLog("✓ 安全备份完成（" + human(safety.length()) + "）");
                } else {
                    cb.onLog("⚠ 安全备份失败，仍继续导入（原数据将被覆盖）");
                }

                cb.onLog("开始导入：" + kind.label);
                boolean ok = runRestore(context, kind, src, cb);

                if (ok) {
                    cb.onLog("✓ 导入完成");
                    if (restartAfter) restart(context, cb);
                    cb.onDone(true, null, null);
                } else {
                    if (restartAfter) restart(context, cb);
                    cb.onDone(false, null, "导入失败（可用 " + safety.getName() + " 恢复）");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "import failed", e);
                cb.onDone(false, null, String.valueOf(e.getMessage()));
            }
        }, "nf-import").start();
    }

    private static boolean runRestore(@NonNull Context context, @NonNull Kind kind,
                                      @NonNull File src, @NonNull Callback cb) {
        String name = NarraForkController.CONTAINER_NAME;
        String cmd;

        switch (kind) {
            case ROOTFS:
                // proot-distro restore 没有 --name：容器名由归档内部结构决定（实测其
                // --help 只有 -v/-q），且"一个归档只恢复一个容器"。因此先删掉同名旧
                // 容器，避免 restore 遇到已存在的目标。
                cmd = "export PATH=\"" + bin() + ":$PATH\"; "
                    + bin() + "/proot-distro remove " + name + " || true; "
                    + bin() + "/proot-distro restore '" + src.getAbsolutePath() + "'";
                break;

            case HOME:
            case NF_DATA: {
                String target = (kind == Kind.HOME) ? "root" : "root/.narrafork";
                // 解到 / 下覆盖。用 --same-owner 保持权限；容器内是 root 身份。
                cmd = "export PATH=\"" + bin() + ":$PATH\"; "
                    + "cat '" + src.getAbsolutePath() + "' | "
                    + bin() + "/proot-distro login " + name
                    + " -- tar xzf - -C / --same-owner";
                break;
            }

            default:
                return false;
        }

        Result r = sh(context, cmd, "nf-import-" + kind.slug);
        if (!r.ok) {
            cb.onLog("导入失败 (exit=" + r.exit + ")");
            if (!r.err.isEmpty()) cb.onLog("stderr: " + tail(r.err));
            if (!r.out.isEmpty()) cb.onLog("stdout: " + tail(r.out));
        }
        return r.ok;
    }

    /**
     * 校验包结构：能否被 tar 列出，且顶层条目符合该类型的预期。
     * 结构不对就直接拒绝，而不是解一半留下个坏掉的环境。
     */
    private static boolean verifyArchive(@NonNull Context context, @NonNull Kind kind,
                                         @NonNull File src, @NonNull Callback cb) {
        String cmd = "tar tzf '" + src.getAbsolutePath() + "' 2>/dev/null | head -40";
        Result r = sh(context, cmd, "nf-verify");
        if (!r.ok || r.out.isEmpty()) {
            cb.onLog("无法读取包内容（可能不是有效的 tar.gz）");
            return false;
        }

        String listing = r.out;
        switch (kind) {
            case HOME:
                // 期望顶层是 root/
                if (!listing.contains("root/")) {
                    cb.onLog("包内未找到 root/，这可能不是「用户目录」备份");
                    return false;
                }
                return true;
            case NF_DATA:
                if (!listing.contains("root/.narrafork")) {
                    cb.onLog("包内未找到 root/.narrafork，这可能不是「NarraFork 数据」备份");
                    return false;
                }
                return true;
            case ROOTFS:
                // proot-distro backup 的包顶层是 rootfs 内容（bin/ etc/ usr/ 之类）
                if (!(listing.contains("bin") || listing.contains("etc") || listing.contains("usr"))) {
                    cb.onLog("包内未找到 rootfs 结构，这可能不是「整个容器」备份");
                    return false;
                }
                return true;
            default:
                return false;
        }
    }

    // ── 服务生命周期 ────────────────────────────────────────────────────────

    /** 停服务并等待端口真正关闭。返回 false 表示超时（调用方应放弃操作）。 */
    private static boolean stopAndWait(@NonNull Context context, @NonNull Callback cb) {
        int port = NarraForkController.getPort(context);
        if (!NarraForkController.isRunning(port, 1000)) {
            cb.onLog("服务本来就没在运行");
            return true;
        }

        NarraForkController.stop(context);
        for (int i = 0; i < 30; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return false;
            }
            if (!NarraForkController.isRunning(port, 800)) {
                cb.onLog("✓ 服务已停止（" + (i + 1) + "s）");
                return true;
            }
        }
        cb.onLog("✗ 等待服务停止超时（30s）");
        return false;
    }

    private static void restart(@NonNull Context context, @NonNull Callback cb) {
        cb.onLog("重新启动 NarraFork…");
        NarraForkController.start(context);
    }

    /** bootstrap 是否就绪。未就绪时给出明确说明而不是空洞的失败。 */
    private static boolean envReady(@NonNull Callback cb) {
        File bash = new File(bin() + "/bash");
        if (!bash.canExecute()) {
            cb.onDone(false, null, "运行环境尚未就绪（找不到 " + bash + "）");
            return false;
        }
        if (!new File(bin() + "/proot-distro").exists()) {
            cb.onDone(false, null, "运行环境尚未就绪（找不到 proot-distro）");
            return false;
        }
        return true;
    }

    // ── shell 执行 ──────────────────────────────────────────────────────────

    private static final class Result {
        final boolean ok;
        final Integer exit;
        final String out;
        final String err;

        Result(boolean ok, Integer exit, String out, String err) {
            this.ok = ok;
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    /** 同步执行（须在后台线程调用）。 */
    private static Result sh(@NonNull Context context, @NonNull String cmd, @NonNull String label) {
        ExecutionCommand ec = new ExecutionCommand(-1, bin() + "/bash",
            new String[]{"-lc", cmd}, null, TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.APP_SHELL.getName(), false);
        ec.commandLabel = label;
        AppShell shell = AppShell.execute(context.getApplicationContext(), ec, null,
            new TermuxShellEnvironment(), null, true);

        Integer exit = ec.resultData != null ? ec.resultData.exitCode : null;
        String out = (ec.resultData != null && ec.resultData.stdout != null)
            ? ec.resultData.stdout.toString().trim() : "";
        String err = (ec.resultData != null && ec.resultData.stderr != null)
            ? ec.resultData.stderr.toString().trim() : "";
        boolean ok = shell != null && exit != null && exit == 0;
        return new Result(ok, exit, out, err);
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    @NonNull
    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String tail(@NonNull String s) {
        int max = 1000;
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }
}
