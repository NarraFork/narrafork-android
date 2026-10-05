# NarraFork Android（Termux + proot Debian 12）

即开即用的 NarraFork 安卓运行环境：一个预构建的 Debian 12 proot 容器，内置 `git`、`dtach`、`ripgrep`、`sqlite3` 等工具，自带可切换的国内镜像源，预打包 NarraFork `linux-arm64` 二进制（靠应用内自更新演进）。

## 手机上 3 步

```bash
# 1. 安装 Termux（F-Droid 或 GitHub Releases，不要用 Play 商店旧版）

# 2. 跑安装脚本（在线；或 --local 离线）
curl -sL <install.sh 地址> | bash
# 离线：把 dist/*.tar.gz 和 .sha256 拷到手机，然后
bash install.sh --local /sdcard/Download/narrafork-android-rootfs-<ver>-arm64.tar.gz

# 3. 启动
narrafork
```

浏览器打开 `http://127.0.0.1:7779`（本机）或 `http://<手机局域网IP>:7779`。

## 包含什么

| 组件 | 说明 |
|------|------|
| Debian 12 (bookworm) arm64 | proot 容器，无需 root |
| git 2.39 / dtach 0.9 / ripgrep 13 / sqlite3 | NarraFork 运行所需外部命令 |
| `narrafork-start` | 启动器，处理自更新后的「并列二进制」选择 |
| `narrafork-mirror` | apt 镜像切换器（deb822） |
| narrafork linux-arm64 二进制 | 预打包，可在应用内自更新 |

## 镜像源切换

容器内：

```bash
proot-distro login narrafork -- narrafork-mirror list      # 看可用镜像、标出当前
proot-distro login narrafork -- narrafork-mirror ustc      # 切到中科大
proot-distro login narrafork -- narrafork-mirror tuna      # 切回清华
```

可用镜像：`tuna`（清华）、`ustc`（中科大）、`aliyun`、`huawei`（华为云）、`tencent`（腾讯云）、`nju`（南大）、`bfsu`（北外）、`official`（deb.debian.org）。

> 技术细节：Debian 12 用 deb822 格式（`/etc/apt/sources.list.d/debian.sources`），切换器按 stanza 重写 `URIs:`，main 源与 security 源自动对齐（security 为 `<base>-security`）。基础镜像无 CA 证书时自动降级 http 引导，装好证书后可切回 https。

## 自更新后如何选对二进制

NarraFork 自更新**不会覆盖**当前二进制，而是把新版并列放到 `/opt/narrafork/`，并写 `~/.narrafork/updates/placed-update.json`。`narrafork-start` 启动时按以下顺序选第一个通过校验的：

1. `placed-update.json` 里的 `newBinaryPath`（要求存在 + `sizeBytes` 与实际一致 + 可执行）
2. `/opt/narrafork/` 下版本号最新的 `narrafork-*-linux-arm64*`
3. 打包时装的固定名 `/opt/narrafork/narrafork`

所以无论更新多少次、目录里并列多少个版本，冷启动都会跑「正确」的那个。

## 本机构建（开发）

```bash
bun scripts/build-binary.ts      # 把主仓最新 linux-arm64 二进制放入 overlays（--build 强制重建）
bun scripts/build-rootfs.ts      # podman 交叉构建 arm64 rootfs → dist/*.tar.gz + .sha256 + manifest
bash tests/verify-rootfs.sh      # 解包冒烟：工具齐全、ELF 魔数、镜像切换、选二进制逻辑
```

构建产物在 `dist/`：
- `narrafork-android-rootfs-<ver>-arm64.tar.gz`（约 100–150MB）
- `….sha256`（安装时校验）
- `…-arm64.json`（manifest）

## 备注

- proot-distro 5.x 拒绝嵌套 proot：`install.sh` 必须在 **Termux 主环境**跑，不能在已有容器里跑。
- 本地 Podman 容器在 Android/proot 下不可用（NarraFork 会提示改用远程容器主机），这是预期降级。
- 本项目不改动 narrafork 主仓；`build-binary.ts` 只读取主仓 `dist/` 产物或调用其构建脚本。

## 真机验证（设备回线后跑一遍）

把产物推到一台 **arm64 安卓 + Termux** 设备，验证「即开即用」全链路：

```bash
# 本机：把包和安装脚本放到可下载处，或直接用 adb push 到 /sdcard/Download/
adb push dist/narrafork-android-rootfs-0.1.0-arm64.tar.gz /sdcard/Download/
adb push dist/narrafork-android-rootfs-0.1.0-arm64.tar.gz.sha256 /sdcard/Download/
adb push termux/install.sh termux/mirrors.json /sdcard/Download/

# 手机 Termux 里：
cp /sdcard/Download/{install.sh,mirrors.json} ~ && cd ~
bash install.sh --local /sdcard/Download/narrafork-android-rootfs-0.1.0-arm64.tar.gz --no-start
narrafork            # 启动后浏览器开 http://127.0.0.1:7779
```

预期：① 换源 + `pkg install proot-distro` 成功；② 解包为 `narrafork` 容器；③ `narrafork` 命令拉起服务；④ `proot-distro login narrafork -- narrafork-mirror list` 能列出镜像。设备端不做 `apt` 装包（rootfs 已预装），故全程只有「下载/解压」耗时。

