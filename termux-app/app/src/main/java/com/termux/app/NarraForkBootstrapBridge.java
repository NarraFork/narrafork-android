package com.termux.app;

import android.app.Activity;

import androidx.annotation.NonNull;

/**
 * 让 NarraFork 的自定义主界面也能触发 Termux bootstrap 解包。
 *
 * 背景（这是一个实测踩到的真 bug）：原版 Termux 只在 {@link TermuxActivity#onCreate} 里调
 * {@code TermuxInstaller.setupBootstrapIfNeeded(...)}，因为终端界面就是唯一入口。而
 * NarraFork Box 把 LAUNCHER 换成了 NarraForkDashboardActivity，用户如果不去点「终端」，
 * bootstrap 就永远不会解包 —— 此时 $PREFIX/bin/bash 并不存在，安装器用 AppShell 跑
 * proot-distro 时进程根本起不来，表现为 exitCode 为 null、stdout/stderr 全空的
 * 「导入失败」，非常难定位。
 *
 * {@code TermuxInstaller} 与其方法都是包私有的，而 Dashboard 位于子包
 * {@code com.termux.app.narrafork}，无法直接访问；因此在本包内提供这个桥接。
 * 刻意只暴露这一个方法，不放宽 TermuxInstaller 本身的可见性。
 */
public final class NarraForkBootstrapBridge {

    private NarraForkBootstrapBridge() {}

    /**
     * 按需解包 bootstrap。已解包时 Termux 自身的实现会直接回调 whenDone，因此可安全重复调用。
     *
     * @param activity 需要 Activity 以显示进度对话框与错误弹窗（Termux 原实现要求）
     * @param whenDone 解包完成（或本来就已完成）后在主线程回调
     */
    public static void setupBootstrapIfNeeded(@NonNull Activity activity, @NonNull Runnable whenDone) {
        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
    }
}
