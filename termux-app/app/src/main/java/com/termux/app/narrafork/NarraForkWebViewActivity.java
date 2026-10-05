package com.termux.app.narrafork;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

/**
 * 内置 WebView：加载 NarraFork Web 界面（http://127.0.0.1:<port>）。
 */
public final class NarraForkWebViewActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "narrafork_url";

    private WebView webView;
    private ProgressBar progress;
    private TextView errorView;
    private String url;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_narrafork_webview);

        url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) url = NarraForkController.getUrl(this);

        webView = findViewById(R.id.narrafork_webview);
        progress = findViewById(R.id.narrafork_progress);
        errorView = findViewById(R.id.narrafork_error);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 不开 wide viewport：那会让 WebView 用桌面宽度（980px）渲染，NarraFork 于是
        // 走桌面布局，在手机/平板上表现为内容挤在中间、两侧大片空白。
        // 关掉后视口宽度等于实际屏幕宽度，页面能正确按响应式断点排布。
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
                errorView.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                progress.setVisibility(View.GONE);
                syncStatusBarWithPage(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    errorView.setVisibility(View.VISIBLE);
                    errorView.setText("无法连接 NarraFork（" + url + "）。\n请回到菜单确认服务已启动。");
                }
            }

            /**
             * 应用内信任 NarraFork 的本地 CA，无需把证书装进系统证书库。
             *
             * 判定全部交给 NarraForkTrust：必须同时满足「回环地址」且「证书由本机
             * ~/.narrafork/tls/ca.pem 验签通过」才放行。任何一条不满足就 cancel()，
             * 所以这不是"忽略证书错误"，而是把信任锚点换成我们自己那张 CA，且作用域
             * 仅限本应用的 WebView。
             */
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                if (NarraForkTrust.shouldTrust(error, url)) {
                    handler.proceed();
                    return;
                }
                handler.cancel();
                progress.setVisibility(View.GONE);
                errorView.setVisibility(View.VISIBLE);
                errorView.setText("证书校验未通过，已阻止加载。\n"
                    + "仅接受本机 NarraFork CA 签发的证书（回环地址）。");
            }
        });

        webView.loadUrl(url);
    }

    /**
     * 返回键：先退页面历史，退到底再离开界面。
     *
     * 但不能只依赖这个。NarraFork 是单页应用，前端路由会不断往 WebView 历史里压条目，
     * 于是返回键可能长时间只在页面内后退，用户会觉得「退不出去」。因此界面上另外提供了
     * 一个悬浮的「菜单」按钮作为确定能用的出路（见 activity_narrafork_webview.xml）。
     */
    /**
     * 让系统状态栏跟随 NarraFork 页面的配色。
     *
     * NarraFork 前端会根据当前主题（暗色 / OLED 纯黑 / 亮色）实时更新
     * `<meta name="theme-color">`，这正是"页面顶栏是什么颜色"的权威来源。读它比在
     * App 侧硬编码一个颜色可靠：用户在 NarraFork 里切主题时，状态栏能跟着变。
     *
     * 读不到就保持主题里的默认值（narrafork_theme），不做任何猜测。
     */
    private void syncStatusBarWithPage(@Nullable WebView view) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        view.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=\"theme-color\"]');"
                + "return m?m.getAttribute('content'):null;})()",
            value -> {
                String hex = parseJsString(value);
                if (hex == null) return;
                Integer color = parseCssColor(hex);
                if (color == null) return;
                applyStatusBarColor(color);
            });
    }

    /** evaluateJavascript 返回的是 JSON 字面量（带引号或 "null"）。 */
    @Nullable
    private static String parseJsString(@Nullable String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || "null".equals(s)) return null;
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace("\\\"", "\"").trim();
        return s.isEmpty() ? null : s;
    }

    /** 只接受 #rgb / #rrggbb 形式；其它（rgb()、颜色名）保持默认而不是瞎猜。 */
    @Nullable
    private static Integer parseCssColor(@NonNull String s) {
        if (!s.startsWith("#")) return null;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void applyStatusBarColor(int color) {
        Window w = getWindow();
        w.setStatusBarColor(color);
        // 深色底用浅色图标，浅色底用深色图标，否则图标会看不见。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            boolean lightBg = isLight(color);
            if (lightBg) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    /** 相对亮度判定（sRGB 加权），用于决定状态栏图标明暗。 */
    private static boolean isLight(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luma > 0.5;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
