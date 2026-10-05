package com.termux.app.narrafork;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 备份 / 恢复界面。
 *
 * 三种粒度各有导出与导入。导入走系统文件选择器（避免手输路径），选中的 Uri 会先拷到
 * 应用缓存再交给 shell —— content:// Uri 在 shell 里没有意义。
 */
public final class NarraForkBackupActivity extends AppCompatActivity {

    private static final String LOG_TAG = "NarraForkBackupActivity";

    private static final int REQ_STORAGE = 1001;
    private static final int REQ_PICK_BASE = 2000; // + kind.ordinal()

    private TextView dirView;
    private TextView logView;
    private ScrollView logScroll;
    private Button share;
    private final Button[] allButtons = new Button[6];

    @Nullable
    private File lastExport;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_narrafork_backup);

        dirView = findViewById(R.id.nfb_dir);
        logView = findViewById(R.id.nfb_log);
        logScroll = findViewById(R.id.nfb_log_scroll);
        share = findViewById(R.id.nfb_share);

        dirView.setText("导出目录：" + NarraForkBackup.exportDir().getAbsolutePath());

        Button expRootfs = findViewById(R.id.nfb_exp_rootfs);
        Button expHome = findViewById(R.id.nfb_exp_home);
        Button expData = findViewById(R.id.nfb_exp_nfdata);
        Button impRootfs = findViewById(R.id.nfb_imp_rootfs);
        Button impHome = findViewById(R.id.nfb_imp_home);
        Button impData = findViewById(R.id.nfb_imp_nfdata);

        allButtons[0] = expRootfs;
        allButtons[1] = expHome;
        allButtons[2] = expData;
        allButtons[3] = impRootfs;
        allButtons[4] = impHome;
        allButtons[5] = impData;

        expRootfs.setOnClickListener(v -> doExport(NarraForkBackup.Kind.ROOTFS));
        expHome.setOnClickListener(v -> doExport(NarraForkBackup.Kind.HOME));
        expData.setOnClickListener(v -> doExport(NarraForkBackup.Kind.NF_DATA));

        impRootfs.setOnClickListener(v -> pickFor(NarraForkBackup.Kind.ROOTFS));
        impHome.setOnClickListener(v -> pickFor(NarraForkBackup.Kind.HOME));
        impData.setOnClickListener(v -> pickFor(NarraForkBackup.Kind.NF_DATA));

        share.setOnClickListener(v -> shareLast());

        ensureStoragePermission();
    }

    // ── 权限 ────────────────────────────────────────────────────────────────

    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            appendLog("请求存储权限（导出到 /sdcard 需要）…");
            requestPermissions(new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        appendLog(granted ? "✓ 已获得存储权限" : "✗ 未获得存储权限，导出到 /sdcard 会失败");
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    private void doExport(@NonNull NarraForkBackup.Kind kind) {
        setBusy(true);
        appendLog("");
        appendLog("=== 导出：" + kind.label + " ===");
        NarraForkBackup.export(this, kind, true, new NarraForkBackup.Callback() {
            @Override
            public void onLog(@NonNull String line) {
                runOnUiThread(() -> appendLog(line));
            }

            @Override
            public void onDone(boolean success, @Nullable File outFile, @Nullable String error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    if (success && outFile != null) {
                        lastExport = outFile;
                        share.setEnabled(true);
                        appendLog("产物：" + outFile.getAbsolutePath());
                    } else {
                        appendLog("✗ 导出失败：" + error);
                    }
                });
            }
        });
    }

    // ── 导入 ────────────────────────────────────────────────────────────────

    private void pickFor(@NonNull NarraForkBackup.Kind kind) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_BASE + kind.ordinal());
        } catch (Exception e) {
            appendLog("✗ 无法打开文件选择器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode < REQ_PICK_BASE || requestCode > REQ_PICK_BASE + 10) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        NarraForkBackup.Kind kind = NarraForkBackup.Kind.values()[requestCode - REQ_PICK_BASE];
        Uri uri = data.getData();

        setBusy(true);
        appendLog("");
        appendLog("=== 导入：" + kind.label + " ===");
        appendLog("准备文件…");

        new Thread(() -> {
            // content:// Uri 传给 shell 没有意义，必须先落到真实路径。
            File staged = stageUri(uri);
            runOnUiThread(() -> {
                if (staged == null) {
                    setBusy(false);
                    appendLog("✗ 无法读取所选文件");
                    return;
                }
                confirmImport(kind, staged);
            });
        }, "nf-stage").start();
    }

    @Nullable
    private File stageUri(@NonNull Uri uri) {
        File dst = new File(getCacheDir(), "import-staged.tar.gz");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return null;
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return dst;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "stageUri failed", e);
            return null;
        }
    }

    private void confirmImport(@NonNull NarraForkBackup.Kind kind, @NonNull File src) {
        new AlertDialog.Builder(this)
            .setTitle("确认导入")
            .setMessage("即将用所选备份覆盖「" + kind.label + "」。\n\n"
                + "大小：" + NarraForkBackup.human(src.length()) + "\n\n"
                + "导入前会自动把当前数据备份到导出目录，失败时可用它恢复。\n\n"
                + "继续吗？")
            .setNegativeButton("取消", (d, w) -> {
                setBusy(false);
                appendLog("已取消");
            })
            .setPositiveButton("确认导入", (d, w) -> runImport(kind, src))
            .setCancelable(false)
            .show();
    }

    private void runImport(@NonNull NarraForkBackup.Kind kind, @NonNull File src) {
        NarraForkBackup.importFrom(this, kind, src, true, new NarraForkBackup.Callback() {
            @Override
            public void onLog(@NonNull String line) {
                runOnUiThread(() -> appendLog(line));
            }

            @Override
            public void onDone(boolean success, @Nullable File outFile, @Nullable String error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    appendLog(success ? "✓ 导入成功" : ("✗ 导入失败：" + error));
                    src.delete(); // 清理暂存文件
                });
            }
        });
    }

    // ── 分享 ────────────────────────────────────────────────────────────────

    private void shareLast() {
        if (lastExport == null || !lastExport.isFile()) {
            appendLog("没有可分享的文件");
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".export", lastExport);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("application/gzip");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "分享备份"));
        } catch (Exception e) {
            appendLog("✗ 分享失败：" + e.getMessage());
            Logger.logStackTraceWithMessage(LOG_TAG, "share failed", e);
        }
    }

    // ── UI 辅助 ─────────────────────────────────────────────────────────────

    private void setBusy(boolean busy) {
        for (Button b : allButtons) {
            if (b != null) b.setEnabled(!busy);
        }
        if (busy) share.setEnabled(false);
        else if (lastExport != null) share.setEnabled(true);
    }

    private void appendLog(@NonNull String line) {
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
