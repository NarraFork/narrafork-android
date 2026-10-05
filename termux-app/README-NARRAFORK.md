# NarraFork Box — 自定义 Termux 应用

独立包名（`com.narrafork.box`）的自定义 Termux 构建：主界面是**自定义菜单**，可配端口、一键启动 NarraFork，并用**内置 WebView** 直接使用。**可与官方 Termux 共存**。

## 产物

`dist/narrafork-box-0.1.0-arm64.apk`（276MB，arm64-v8a）

已验证：
- 包名 `com.narrafork.box`，标签 `NarraFork`
- 入口 `com.termux.app.narrafork.NarraForkDashboardActivity`（自定义菜单，非终端）
- 自签名（`CN=NarraFork Box`）
- 内嵌 bootstrap 的 `bin/bash` 硬编码前缀 = `/data/data/com.narrafork.box/files/usr/...`（19578 个文件）
- 内嵌 rootfs 资产 `assets/narrafork-rootfs-arm64.bin`（Stored 未二次压缩，sha256 与源包逐字节一致）

## 功能

| 功能 | 说明 |
|------|------|
| 自定义菜单主界面 | 状态灯 + 运行地址 + 端口输入 + 日志区 |
| 端口配置 | 菜单里直接改，存 SharedPreferences，下次启动生效 |
| 一键启动/停止 | 经 `AppShell` 后台执行，无需可见终端 |
| 内置 WebView | 打开 `http://127.0.0.1:<port>`，含加载中/失败提示 |
| 终端逃生通道 | 菜单可跳进完整 Termux 终端排查 |
| 即开即用 | rootfs（Debian 12 + git/dtach/rg/sqlite3 + narrafork）打进 APK，首启自动导入，全程离线 |

## 首启流程

1. TermuxInstaller 解包内嵌 bootstrap（新 PREFIX，自带 `proot` + `proot-distro`）
2. `NarraForkInstaller` 从 assets 流式拷出 rootfs → `proot-distro install --name narrafork`
3. 写 `~/.narrafork-box/start.sh`（注入 PORT）
4. 点「启动」→ 容器内 `narrafork-start` 拉起服务 → 点「打开 NarraFork」进 WebView

## 自更新后如何选对二进制

NarraFork 自更新**不覆盖**当前二进制，新版并列放到容器 `/opt/narrafork/` 并写 `placed-update.json`。容器内 `narrafork-start` 按以下顺序选第一个通过校验的：

1. `placed-update.json` 的 `newBinaryPath`（存在 + sizeBytes 一致 + 可执行）
2. `/opt/narrafork/` 下版本号最新的 `narrafork-*-linux-arm64*`
3. 打包时装的固定名 `/opt/narrafork/narrafork`

所以无论更新多少次、并列多少个版本，冷启动都跑对的那个。

## 构建复现

```bash
export ANDROID_HOME=~/Android/Sdk

# 1. 重建 bootstrap（改包名后必须，否则 PREFIX 不匹配装上无法启动）
cd ~/projects/termux-packages    # scripts/properties.sh: TERMUX_APP__PACKAGE_NAME="com.narrafork.box"
podman run --rm --userns=keep-id \
  -e GIT_CONFIG_GLOBAL=/home/builder/gitconfig-nf \
  --volume ~/projects/termux-packages:/home/builder/termux-packages:U \
  --volume ~/projects/termux-box-build/gitconfig:/home/builder/gitconfig-nf:ro \
  --volume ~/projects/termux-box-build/bin/curl:/usr/local/bin/curl:ro \
  --volume ~/projects/termux-box-build/termux-build-cache:/home/builder/.termux-build:U \
  --cap-add CAP_SYS_ADMIN --device /dev/fuse \
  ghcr.nju.edu.cn/termux/package-builder:latest \
  ./scripts/build-bootstraps.sh --architectures aarch64 --add proot-distro
cp bootstrap-aarch64.zip ~/projects/termux-app/app/src/main/cpp/
# 把新 sha256 填进 app/build.gradle 的 downloadBootstrap("aarch64", ...)

# 2. 放 rootfs 资产（注意用 .bin 后缀，见下方踩坑）
cp ~/projects/narrafork-android/dist/narrafork-android-rootfs-*-arm64.tar.gz \
   ~/projects/termux-app/app/src/main/assets/narrafork-rootfs-arm64.bin

# 3. 出 APK
cd ~/projects/termux-app
./gradlew :app:assembleRelease -Pandroid.injected.build.abi=arm64-v8a
```

## 构建踩坑记录（都是实测踩到并已解决的）

**网络类**（构建机在国内）：
- Gradle wrapper 默认 10s 超时下不动 → `gradle-wrapper.properties` 指向腾讯云镜像
- `ghcr.io/termux/package-builder`（7.38GB）拉不动 → 用南大镜像 `ghcr.nju.edu.cn`，且必须带 `--retry`（镜像站对 Range 续传兼容差，podman 内置重试会复用已完成层）
- bootstrap zip 从 GitHub releases 下载被重置 → 预下载放 `app/src/main/cpp/`，**并同步更新 build.gradle 里的 sha256**（否则会被判哈希错误删掉重下官方版）
- `android.googlesource.com` 被墙（libandroid-selinux 走 git clone）→ `GIT_CONFIG_GLOBAL` 注入 `insteadOf` 重定向到清华 AOSP 镜像
- GitHub tarball 下载慢/断 → `/usr/local/bin/curl` 包装器把 github URL 重写到 `gh-proxy.com` 并加强重试
- `dist.schmorp.de`（ncurses 要的 rxvt-unicode）、`torproject.org`（tor）完全不可达 → 从 Debian pool 预下载同版本源码填进 `.termux-build/<pkg>/cache/`（**校验和必须一致**，两者实测都对得上）

**权限类**（rootless podman）：
- 容器 `builder`(1001) 写不了挂载的 `output/` → 用 `--userns=keep-id` + `:U` 卷选项
- keep-id 会把宿主文件属主推成 subuid，宿主随后无法编辑 → 需要时用 `podman run --user 0 ... chown -R 0:0`（容器 uid 0 映射到宿主当前用户）

**上游脚本 bug**（已在本地修）：
- `build-bootstraps.sh` 清单里的 `bzip2` 已不存在（重构为 `libbz2` 的子包），报 "No package bzip2 found" → 改为 `libbz2`
- `termux_step_install_license.sh` 的文件名变体生成顺序有缺陷：先加扩展名再整体大写，只产出 `License.txt`/`LICENSE.TXT`，**never `LICENSE.txt`** → python-pip、libandroid-utimes 这类用 `LICENSE.txt`/`LICENSE.md` 的包会误报 "Could not find a license file"。已改为先生成大小写变体再加扩展名（变体数 48→192，覆盖全部常见拼写）

**打包类**：
- `.tar.gz` 放 assets 会被 aapt **解压**成 `.tar`（137MB → 412MB，且文件名变了）→ 改用 `.bin` 后缀 + `aaptOptions.noCompress`，实测 APK 内为 `Stored` 且 sha256 与源包一致
- 改了 `cpp/*.zip` 后 gradle 认为 native 任务 up-to-date，APK 不会更新 → 需 `rm -rf app/build/intermediates/{cxx,merged_native_libs,apk}` 或 `--rerun-tasks`
- 只发 arm64：其余架构没有以新包名重编译的 bootstrap，故 `ndk.abiFilters 'arm64-v8a'` 且不再下载其他架构（官方版 PREFIX 是 `com.termux`，装上必崩）

## 待办

- **真机验证**：需 arm64 安卓设备实测「装 APK → 菜单配端口 → 一键启动 → WebView 打开」。pad7s 当前离线，且它本身已在 proot 内（proot-distro 拒绝嵌套），需真实 Termux 宿主环境。

## 相关项目

- `~/projects/narrafork-android` — rootfs 构建器（本 APK 内嵌的 Debian 12 rootfs 由它产出）
- `~/projects/termux-packages` — bootstrap 重编译（已改包名 + 两处上游 bug 修复）
- `~/projects/termux-box-build` — 构建辅助（gitconfig 重定向、curl 包装器、预填缓存、留档的官方 bootstrap）
