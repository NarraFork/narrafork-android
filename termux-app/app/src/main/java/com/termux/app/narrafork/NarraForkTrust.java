package com.termux.app.narrafork;

import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * 应用内 TLS 信任判定：只信任 NarraFork 自己生成的那张根 CA，且只对回环地址生效。
 *
 * 为什么需要这个：NarraFork 在本机 proot 容器里以 HTTPS 提供服务，用的是它自己生成的
 * 本地根 CA（~/.narrafork/tls/ca.pem）签发的证书。系统证书库里当然没有这张 CA，所以
 * WebView 会报 SSL_UNTRUSTED。让用户去「设置 → 安全 → 安装证书」把 CA 装进系统，
 * 既繁琐又会把这张 CA 的信任范围扩大到整台设备上的所有应用。
 *
 * 这里改为在应用内部判定，信任范围仅限本应用的 WebView：
 *
 *   1. host 必须是回环地址（127.0.0.1 / localhost / ::1）。非回环一律拒绝 ——
 *      不能因为「我们信任自己的 CA」就顺带放行任意远端主机。
 *   2. 服务器证书必须能被 ca.pem 里的公钥验签。这一步是关键：只判回环地址而不验证书链，
 *      等于同设备上任何抢占了该端口的进程都能冒充 NarraFork 而我们无从察觉。
 *   3. 两条都满足才 proceed()，否则 cancel()。
 *
 * 绝不无条件 proceed()：那等于对该 WebView 关闭全部证书校验，是这类"修好了"的改动里
 * 最常见也最危险的做法。
 */
public final class NarraForkTrust {

    private static final String LOG_TAG = "NarraForkTrust";

    private NarraForkTrust() {}

    /** ca.pem 在容器内的路径（rootfs 内 /root/.narrafork/tls/ca.pem 的宿主侧映射）。 */
    @NonNull
    public static File caFile() {
        // proot-distro 容器 rootfs 在 $PREFIX/var/lib/proot-distro/containers/<name>/rootfs
        return new File(
            com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
                + "/var/lib/proot-distro/containers/" + NarraForkController.CONTAINER_NAME
                + "/rootfs/root/.narrafork/tls/ca.pem");
    }

    /** 回环地址判定。 */
    public static boolean isLoopback(@Nullable String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isEmpty()) return false;
        String host = urlOrHost;
        if (urlOrHost.contains("://")) {
            try {
                host = Uri.parse(urlOrHost).getHost();
            } catch (Exception e) {
                return false;
            }
        }
        if (host == null) return false;
        host = host.trim();
        // 去掉 IPv6 字面量的方括号
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return "127.0.0.1".equals(host)
            || "localhost".equalsIgnoreCase(host)
            || "::1".equals(host)
            || "0:0:0:0:0:0:0:1".equals(host);
    }

    /**
     * 判定一个 SslError 是否应被放行。
     *
     * @param error WebView 报的 SSL 错误
     * @param pageUrl 当前加载的 URL（用于 host 校验）
     */
    public static boolean shouldTrust(@Nullable SslError error, @Nullable String pageUrl) {
        if (error == null) return false;

        // ── 1. 只对回环地址生效 ────────────────────────────────────────────────
        // 同时校验页面 URL 与证书报错所属的 URL：任一非回环即拒绝。
        if (!isLoopback(pageUrl)) {
            Logger.logWarn(LOG_TAG, "拒绝：页面 URL 非回环地址");
            return false;
        }
        String errUrl = error.getUrl();
        if (errUrl != null && !errUrl.isEmpty() && !isLoopback(errUrl)) {
            Logger.logWarn(LOG_TAG, "拒绝：证书错误来源非回环地址");
            return false;
        }

        // ── 2. 服务器证书必须由我们的 CA 签发 ─────────────────────────────────
        X509Certificate serverCert = extractX509(error.getCertificate());
        if (serverCert == null) {
            Logger.logWarn(LOG_TAG, "拒绝：无法取出服务器证书");
            return false;
        }
        X509Certificate ca = loadCa();
        if (ca == null) {
            Logger.logWarn(LOG_TAG, "拒绝：找不到或无法解析 CA（" + caFile().getAbsolutePath() + "）");
            return false;
        }

        try {
            // 用 CA 公钥验签服务器证书。签名对得上，才说明这张证书确实出自我们那张 CA。
            serverCert.verify(ca.getPublicKey());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "拒绝：服务器证书不是本机 CA 签发的（" + e.getClass().getSimpleName() + "）");
            return false;
        }

        // 证书自身有效期（过期的证书即便签名正确也不该放行）
        try {
            serverCert.checkValidity();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "拒绝：服务器证书已过期或尚未生效");
            return false;
        }

        Logger.logInfo(LOG_TAG, "放行：回环地址 + 证书由本机 NarraFork CA 签发");
        return true;
    }

    /** 读取并解析 ca.pem。 */
    @Nullable
    private static X509Certificate loadCa() {
        File f = caFile();
        if (!f.isFile() || f.length() == 0 || f.length() > 1024 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate c = cf.generateCertificate(in);
            return (c instanceof X509Certificate) ? (X509Certificate) c : null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "解析 CA 失败", e);
            return null;
        }
    }

    /**
     * 从 SslCertificate 取出底层 X509Certificate。
     *
     * getX509Certificate() 只在 API 29+ 可用，而本应用 minSdk 21，所以低版本走
     * saveState/restoreState 这条公开且稳定的回落路径（Bundle 里存的是 DER 字节）。
     * 不用反射私有字段：那在不同 ROM 上会静默失效，进而把"验不了签"误判成"不该信任"。
     */
    @Nullable
    private static X509Certificate extractX509(@Nullable SslCertificate sslCert) {
        if (sslCert == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            X509Certificate direct = sslCert.getX509Certificate();
            if (direct != null) return direct;
        }

        try {
            Bundle b = SslCertificate.saveState(sslCert);
            if (b == null) return null;
            byte[] der = b.getByteArray("x509-certificate");
            if (der == null || der.length == 0) return null;
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate c = cf.generateCertificate(new ByteArrayInputStream(der));
            return (c instanceof X509Certificate) ? (X509Certificate) c : null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "从 SslCertificate 取 X509 失败", e);
            return null;
        }
    }

    /** CA 是否已生成（供 UI 判断是否已具备 HTTPS 条件）。 */
    public static boolean caAvailable() {
        File f = caFile();
        return f.isFile() && f.length() > 0;
    }

    /**
     * 构造一个「只信任本机 NarraFork CA」的 SSLSocketFactory，供健康检查等
     * HttpsURLConnection 使用。
     *
     * 注意这里刻意不使用 TrustManager[]{ 全部信任 } 这种写法：那会让健康检查对任何
     * 证书都通过，等于把前面在 WebView 侧建立的 CA 固定又拆掉一次。这里用只装了
     * 我们 CA 的 KeyStore 初始化默认 TrustManager，链校验与主机名校验都保持生效。
     *
     * @return CA 不可用或初始化失败时返回 null（调用方据此跳过 https 探测）
     */
    @Nullable
    public static javax.net.ssl.SSLSocketFactory localCaSocketFactory() {
        X509Certificate ca = loadCa();
        if (ca == null) return null;
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("narrafork-local-ca", ca);

            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "构造本机 CA SSLSocketFactory 失败", e);
            return null;
        }
    }

    /** 供诊断输出：CA 指纹前若干字节，便于确认两端是同一张 CA。 */
    @Nullable
    public static String caFingerprintShort() {
        X509Certificate ca = loadCa();
        if (ca == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(ca.getEncoded());
            byte[] head = Arrays.copyOf(d, 6);
            StringBuilder sb = new StringBuilder();
            for (byte x : head) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
