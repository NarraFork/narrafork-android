#!/usr/bin/env bash
# NarraFork Android 一键安装（在 Termux 里运行，勿在已存在的 proot 内运行）。
#
# 用法：
#   curl -sL <本脚本地址> | bash
#   bash install.sh --local /sdcard/Download/narrafork-android-rootfs-0.1.0-arm64.tar.gz
#   bash install.sh --mirror ustc --name nf
#
# 流程：
#   1) 换 Termux 主仓库为国内镜像（读 mirrors.json）
#   2) pkg install proot-distro
#   3) 下载（或 --local 读本地）rootfs 包并 sha256 校验
#   4) proot-distro install 解包为容器（默认名 narrafork）
#   5) 写入 $PREFIX/bin/narrafork 便捷启动命令
#   6) 打印打开方式（http://<本机IP>:7778）
set -euo pipefail

# ── 可配置项（也可被同名环境变量覆盖）─────────────────────────────────────────
CONTAINER_NAME="${NARRAFORK_CONTAINER:-narrafork}"
MIRROR="${NARRAFORK_TERMUX_MIRROR:-}"          # tuna/ustc/bfsu/official，默认读 mirrors.json
ROOTFS_VERSION="${NARRAFORK_ROOTFS_VERSION:-0.1.0}"
# 预构建 rootfs 包的下发地址（占位：实际由发布流程托管，可用 --local 完全离线绕过）。
ROOTFS_URL="${NARRAFORK_ROOTFS_URL:-}"
PORT="${PORT:-7778}"
START_AFTER="${NARRAFORK_START_AFTER:-1}"

LOCAL_FILE=""
while [ $# -gt 0 ]; do
	case "$1" in
		--local) LOCAL_FILE="${2:-}"; shift 2 ;;
		--local=*) LOCAL_FILE="${1#*=}"; shift ;;
		--mirror) MIRROR="${2:-}"; shift 2 ;;
		--mirror=*) MIRROR="${1#*=}"; shift ;;
		--name) CONTAINER_NAME="${2:-}"; shift 2 ;;
		--name=*) CONTAINER_NAME="${1#*=}"; shift ;;
		--no-start) START_AFTER=0; shift ;;
		*) printf '未知参数: %s\n' "$1" >&2; exit 1 ;;
	esac
done

log() { printf '→ %s\n' "$*"; }
fail() { printf '错误: %s\n' "$*" >&2; exit 1; }

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
MIRRORS_JSON="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/mirrors.json"

# ── 0) 环境自检 ────────────────────────────────────────────────────────────────
case "$PREFIX" in
	*com.termux*) ;;
	*) fail "未检测到 Termux 环境（PREFIX=$PREFIX）。请在 Termux 内运行本脚本，不要在已有的 proot 容器里运行。" ;;
esac
if command -v proot-distro >/dev/null 2>&1 && [ -n "${PROOT_TMP_DIR:-}" ]; then
	fail "检测到已在 proot 内部（proot-distro 不支持嵌套）。请退回 Termux 主环境再运行。"
fi

# ── 1) 换 Termux 源 ────────────────────────────────────────────────────────────
mirror_url() { # $1=name  从 mirrors.json 抓 termux 段对应 URL（无 jq，用 awk 限定 termux 段）
	awk '/"termux"[[:space:]]*:/,/"debian"[[:space:]]*:/' "$MIRRORS_JSON" \
		| sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -n1
}
if [ -z "$MIRROR" ]; then
	# 提取 termux 段整段再找 default（sed 范围要跨过内层 mirrors 的 }）
	MIRROR="$(awk '/"termux"[[:space:]]*:/,/"debian"[[:space:]]*:/' "$MIRRORS_JSON" \
		| sed -n 's/.*"default"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)"
	MIRROR="${MIRROR:-tuna}"
fi
URL="$(mirror_url "$MIRROR")"
[ -n "$URL" ] || fail "mirrors.json 里没有名为 '$MIRROR' 的 Termux 镜像"
log "切换 Termux 主仓库镜像 → $MIRROR ($URL)"
mkdir -p "$PREFIX/etc/termux"
printf '%s\n' "$URL" > "$PREFIX/etc/termux/chosen_mirrors"

log "更新 Termux 软件包索引（pkg update）"
pkg update -y

# ── 2) 安装 proot-distro ───────────────────────────────────────────────────────
if ! command -v proot-distro >/dev/null 2>&1; then
	log "安装 proot-distro"
	pkg install -y proot-distro
else
	log "proot-distro 已安装"
fi

# ── 3) 取得 rootfs 包 ──────────────────────────────────────────────────────────
PKG_FILE=""
if [ -n "$LOCAL_FILE" ]; then
	PKG_FILE="$LOCAL_FILE"
	log "使用本地 rootfs 包: $PKG_FILE"
else
	[ -n "$ROOTFS_URL" ] || fail "未配置下载地址（NARRAFORK_ROOTFS_URL），请用 --local <file> 离线安装"
	PKG_FILE="${TMPDIR:-$PREFIX/tmp}/$(basename "$ROOTFS_URL")"
	log "下载 rootfs 包: $ROOTFS_URL"
	if command -v curl >/dev/null 2>&1; then
		curl -fL "$ROOTFS_URL" -o "$PKG_FILE"
	elif command -v wget >/dev/null 2>&1; then
		wget -O "$PKG_FILE" "$ROOTFS_URL"
	else
		fail "需要 curl 或 wget 来下载（pkg install curl）"
	fi
fi
[ -f "$PKG_FILE" ] || fail "rootfs 包不存在: $PKG_FILE"

# ── 4) sha256 校验（若同名 .sha256 可用）───────────────────────────────────────
if [ -n "$LOCAL_FILE" ] && [ -f "${PKG_FILE}.sha256" ]; then
	log "校验 sha256（本地 sidecar）"
	(cd "$(dirname "$PKG_FILE")" && sha256sum -c "$(basename "${PKG_FILE}.sha256")")
elif [ -z "$LOCAL_FILE" ] && [ -n "$ROOTFS_URL" ]; then
	SHA_URL="${ROOTFS_URL}.sha256"
	SHA_TMP="${PKG_FILE}.sha256"
	if curl -fsL "$SHA_URL" -o "$SHA_TMP" 2>/dev/null; then
		log "校验 sha256（远端 sidecar）"
		(cd "$(dirname "$PKG_FILE")" && sha256sum -c "$(basename "$SHA_TMP")")
	else
		log "未取到 .sha256，跳过校验（建议提供 sidecar）"
	fi
fi

# ── 5) 安装为 proot-distro 容器 ────────────────────────────────────────────────
if proot-distro list 2>/dev/null | grep -q "$CONTAINER_NAME"; then
	log "容器 $CONTAINER_NAME 已存在，跳过安装（如需重装请先 proot-distro remove $CONTAINER_NAME）"
else
	log "解包 rootfs 为容器 $CONTAINER_NAME"
	proot-distro install "$PKG_FILE" --name "$CONTAINER_NAME"
fi

# ── 6) 写入便捷启动命令 ────────────────────────────────────────────────────────
LAUNCHER="$PREFIX/bin/narrafork"
cat > "$LAUNCHER" <<EOF
#!/usr/bin/env bash
# NarraFork Android 便捷启动：进入容器并交给容器内的 narrafork-start
exec proot-distro login "$CONTAINER_NAME" -- /usr/local/bin/narrafork-start "\$@"
EOF
chmod +x "$LAUNCHER"
log "已安装启动命令: narrafork"

# ── 7) 完成 ────────────────────────────────────────────────────────────────────
IP="$(ip -4 addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -n1)"
IP="${IP:-127.0.0.1}"
cat <<EOF

✓ 安装完成。

启动：    narrafork
访问：    http://127.0.0.1:$PORT        （本机）
         http://$IP:$PORT$( [ "$IP" != "127.0.0.1" ] && printf '        （局域网）' )

换容器内 apt 镜像： proot-distro login $CONTAINER_NAME -- narrafork-mirror list
EOF

if [ "$START_AFTER" = "1" ]; then
	log "启动 NarraFork…"
	exec "$LAUNCHER"
fi
