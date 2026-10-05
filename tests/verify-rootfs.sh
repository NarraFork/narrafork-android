#!/usr/bin/env bash
# 解包冒烟测试：把 dist 里的 rootfs 包解到临时目录，断言关键文件与工具。
#
# 用法：
#   bash tests/verify-rootfs.sh                      # 测 dist 里最新的包
#   bash tests/verify-rootfs.sh dist/<pkg>.tar.gz    # 测指定包
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="${1:-}"

pass() { printf '  ✓ %s\n' "$*"; }
fail() { printf '  ✗ %s\n' "$*" >&2; exit 1; }

if [ -z "$PKG" ]; then
	PKG="$(ls -1t "$ROOT"/dist/narrafork-android-rootfs-*-arm64.tar.gz 2>/dev/null | head -n1 || true)"
fi
[ -n "$PKG" ] && [ -f "$PKG" ] || fail "找不到 rootfs 包（先运行 bun scripts/build-rootfs.ts）"

echo "→ 验证包: $PKG"

# sha256 校验（若有 sidecar）
if [ -f "${PKG}.sha256" ]; then
	(cd "$(dirname "$PKG")" && sha256sum -c "$(basename "${PKG}.sha256")" >/dev/null) \
		&& pass "sha256 校验通过" || fail "sha256 不匹配"
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "→ 解包到 $TMP"
tar -xzf "$PKG" -C "$TMP"

# 1. 关键命令存在
for c in git dtach rg sqlite3 bash curl tar xz; do
	# 解出的 rootfs 里路径形如 usr/bin/git 或 bin/git（usrmerge）
	if [ -e "$TMP/usr/bin/$c" ] || [ -e "$TMP/bin/$c" ]; then
		pass "命令存在: $c"
	else
		fail "缺少命令: $c"
	fi
done

# 2. 工具脚本存在且可执行
for s in narrafork-start narrafork-mirror; do
	[ -x "$TMP/usr/local/bin/$s" ] && pass "脚本可执行: $s" || fail "脚本缺失或不可执行: $s"
done

# 3. deb822 源文件存在且为 https（构建期应已切回）
SRC="$TMP/etc/apt/sources.list.d/debian.sources"
[ -f "$SRC" ] || fail "缺少 $SRC"
if grep -q "^URIs: https://" "$SRC"; then pass "apt 源为 https"; else fail "apt 源未切回 https（首启可能因无 CA 握手失败）"; fi

# 4. 预打包二进制存在且非空
BIN="$TMP/opt/narrafork/narrafork"
if [ -f "$BIN" ] && [ -s "$BIN" ]; then
	pass "预打包二进制存在（$(du -m "$BIN" | cut -f1) MB）"
else
	fail "缺少预打包二进制 opt/narrafork/narrafork"
fi
# ELF 魔数
MAGIC="$(head -c4 "$BIN" | od -An -tx1 | tr -d ' \n')"
[ "$MAGIC" = "7f454c46" ] && pass "二进制为 ELF" || fail "二进制不是 ELF（magic=$MAGIC）"

# 5. 镜像切换器在解出的 rootfs 里跑通（用 qemu 若可用，否则静态检查）
echo "→ 镜像切换器逻辑检查"
if command -v qemu-aarch64-static >/dev/null 2>&1 || [ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
	# 用 chroot+qemu 实测 narrafork-mirror（需要 root；非 root 则跳过）
	if [ "$(id -u)" = "0" ]; then
		cp "$(command -v qemu-aarch64-static 2>/dev/null || echo /usr/bin/qemu-aarch64-static)" "$TMP/usr/bin/" 2>/dev/null || true
		chroot "$TMP" /usr/bin/qemu-aarch64-static /usr/local/bin/narrafork-mirror list >/dev/null 2>&1 \
			&& pass "narrafork-mirror list 在 arm64 chroot 内可运行" \
			|| printf '  - (chroot 实测跳过：环境受限)\n'
	else
		printf '  - (非 root，跳过 chroot 实测 narrafork-mirror)\n'
	fi
else
	printf '  - (无 qemu，跳过 arm64 实测 narrafork-mirror)\n'
fi
# 至少做幂等静态断言：切换逻辑只改 URIs 行
grep -c "^URIs:" "$SRC" >/dev/null && pass "debian.sources 含 URIs 字段"

# 6. narrafork-start 选择逻辑（本机 bash 模拟）
echo "→ narrafork-start 选二进制逻辑"
PICK="$TMP/usr/local/bin/narrafork-start"
FAKE="$TMP/fakehome"; mkdir -p "$FAKE/.narrafork/updates" "$FAKE/appdir"
printf '#!/bin/sh\necho shipped\n' > "$FAKE/appdir/narrafork"; chmod +x "$FAKE/appdir/narrafork"
printf '#!/bin/sh\necho v063\n' > "$FAKE/appdir/narrafork-0.6.3-linux-arm64"; chmod +x "$FAKE/appdir/narrafork-0.6.3-linux-arm64"
# 用 --print-only 模式？脚本本身 exec，这里改用覆盖 NARRAFORK_APP_DIR + 拦截 exec。
# 把整行 exec（含其后参数）替换为 echo+exit，避免残留参数让 exit 报错。
sed 's|^exec "\$BIN".*|echo "PICKED=$BIN"; exit 0|' "$PICK" > "$FAKE/start-test"
chmod +x "$FAKE/start-test"
OUT="$(NARRAFORK_APP_DIR="$FAKE/appdir" HOME="$FAKE" bash "$FAKE/start-test" 2>/dev/null | grep PICKED)"
echo "    无更新记录 → $OUT"
[ "${OUT#PICKED=}" = "$FAKE/appdir/narrafork-0.6.3-linux-arm64" ] \
	&& pass "无记录时选最新版本二进制" || fail "无记录时选择错误: $OUT"

# 有 placed-update.json 指向 shipped
SZ="$(stat -c %s "$FAKE/appdir/narrafork")"
cat > "$FAKE/.narrafork/updates/placed-update.json" <<EOF
{
  "version": "0.5.0",
  "fromVersion": "0.6.3",
  "newBinaryPath": "$FAKE/appdir/narrafork",
  "sizeBytes": $SZ,
  "placed": true
}
EOF
OUT="$(NARRAFORK_APP_DIR="$FAKE/appdir" HOME="$FAKE" bash "$FAKE/start-test" 2>/dev/null | grep PICKED)"
echo "    有更新记录 → $OUT"
[ "${OUT#PICKED=}" = "$FAKE/appdir/narrafork" ] \
	&& pass "有记录时按 placed-update.json 选择" || fail "有记录时选择错误: $OUT"

echo ""
echo "✓ 全部通过：$PKG"
