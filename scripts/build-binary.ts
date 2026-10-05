#!/usr/bin/env bun
/**
 * 获取 narrafork linux-arm64 二进制，放入 rootfs/overlays/opt/narrafork/。
 *
 * 默认复用主仓已有的最新 dist 产物（快速、不动主仓）。
 * 用 --build 强制在主仓重新构建（会跑完整 frontend+vite，较慢）。
 *
 * 用法：
 *   bun scripts/build-binary.ts                 # 复用 ../narrafork/dist 最新 arm64
 *   bun scripts/build-binary.ts --build         # 在主仓重新构建 linux-arm64
 *   bun scripts/build-binary.ts --binary=<path> # 指定二进制路径
 *   bun scripts/build-binary.ts --skip          # 完全不处理（overlays 里已有则用现有）
 *   bun scripts/build-binary.ts --from-url [--url=<下载地址>] [--version=x.y.z] [--sha512=<base64>]
 *                                               # 从固定 URL 下载（CI 用；默认读 package.json 的
 *                                               # narraforkBinary：version/sha512/downloadUrl）
 */
import { createHash } from "node:crypto";
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "..");
const MAIN_REPO = resolve(ROOT, "../narrafork");
const OUT_DIR = join(ROOT, "rootfs", "overlays", "opt", "narrafork");
const PLATFORM = "linux-arm64";

function arg(name: string): string | undefined {
	const p = `--${name}=`;
	return process.argv.slice(2).find((a) => a.startsWith(p))?.slice(p.length);
}
const flag = (n: string) => process.argv.includes(`--${n}`);

function latestArm64InDist(): string | undefined {
	const dist = join(MAIN_REPO, "dist");
	if (!existsSync(dist)) return undefined;
	const files = readdirSync(dist)
		.filter((f) => /^narrafork-.+-linux-arm64$/.test(f))
		.map((f) => ({ f, mtime: statSync(join(dist, f)).mtimeMs }))
		.sort((a, b) => b.mtime - a.mtime);
	return files[0] ? join(dist, files[0].f) : undefined;
}

if (flag("skip")) {
	console.log("→ --skip：不动二进制（使用 overlays 中现有内容）。");
	process.exit(0);
}

mkdirSync(OUT_DIR, { recursive: true });

// 注意：binary-info.json 会被 COPY 进发布的 rootfs 包，不得写入本机绝对路径等敏感信息。
function writeMeta(meta: Record<string, unknown>) {
	writeFileSync(join(OUT_DIR, "binary-info.json"), `${JSON.stringify(meta, null, "\t")}\n`);
}

// ── 远程获取模式（CI）─────────────────────────────────────────────────────────
// 版本、sha512 与下载地址固定在 package.json 的 narraforkBinary 字段，保证可复现、
// 可审计；下载后强制校验 sha512。sha512 为 base64 编码（与 update server 元数据格式一致）。
// 下载地址目前是本仓 GitHub Release 的固定资产（vendor-narrafork-<version> tag）——
// update server 只下发 zstd 增量补丁，完整二进制的公开下载端点尚未开放。
if (flag("from-url")) {
	const pkg = JSON.parse(await Bun.file(join(ROOT, "package.json")).text()) as {
		narraforkBinary?: { version?: string; sha512?: string; downloadUrl?: string };
	};
	const version = arg("version") ?? pkg.narraforkBinary?.version;
	const url = arg("url") ?? pkg.narraforkBinary?.downloadUrl;
	if (!version || !url) {
		console.error("❌ 缺少 version/downloadUrl：在 package.json 的 narraforkBinary 固定，或用 --version=/--url= 指定。");
		process.exit(1);
	}
	const filename = `narrafork-${version}-${PLATFORM}`;
	console.log(`→ 下载 ${url}`);
	const res = await fetch(url, { redirect: "follow" });
	if (!res.ok || !res.body) {
		console.error(`❌ 下载失败: HTTP ${res.status} ${res.statusText}`);
		process.exit(1);
	}
	const buf = Buffer.from(await res.arrayBuffer());
	const actualSha = createHash("sha512").update(buf).digest("base64");
	const expectedSha = arg("sha512") ?? pkg.narraforkBinary?.sha512;
	if (expectedSha && actualSha !== expectedSha) {
		console.error(`❌ sha512 校验失败！期望 ${expectedSha}，实际 ${actualSha}`);
		process.exit(1);
	}
	if (!expectedSha) {
		console.log(`  ⚠ 未固定 sha512（建议把实际值写入 package.json narraforkBinary.sha512）: ${actualSha}`);
	}
	const dest = join(OUT_DIR, "narrafork");
	writeFileSync(dest, buf);
	chmodSync(dest, 0o755);
	writeMeta({ name: filename, version, sizeBytes: buf.length, sha512: actualSha, copiedAt: new Date().toISOString() });
	console.log(`✓ 二进制就位: ${dest}  (${filename}, ${(buf.length / 1048576).toFixed(1)} MB, sha512 已校验)`);
	process.exit(0);
}

let src = arg("binary");
if (!src && flag("build")) {
	console.log("→ 在主仓构建 linux-arm64（bun scripts/build-cross-platform.ts --platform=linux-arm64）…");
	const r = Bun.spawnSync(["bun", "scripts/build-cross-platform.ts", "--platform=linux-arm64"], {
		cwd: MAIN_REPO,
		stdio: ["inherit", "inherit", "inherit"],
	});
	if (r.exitCode !== 0) {
		console.error("❌ 主仓构建失败");
		process.exit(1);
	}
	src = latestArm64InDist();
}

if (!src) {
	src = latestArm64InDist();
	if (!src) {
		console.error(`❌ 未在 ${join(MAIN_REPO, "dist")} 找到 narrafork-*-linux-arm64。`);
		console.error("   先运行 --build，或用 --binary=<path> 指定。");
		process.exit(1);
	}
}
src = resolve(src);
if (!existsSync(src)) {
	console.error(`❌ 二进制不存在: ${src}`);
	process.exit(1);
}

// 放进 overlays 时用「固定名 narrafork」，版本信息写入 sidecar，便于 rootfs 内回退。
const dest = join(OUT_DIR, "narrafork");
copyFileSync(src, dest);
chmodSync(dest, 0o755);
const meta = { name: basename(src), sizeBytes: statSync(src).size, copiedAt: new Date().toISOString() };
writeMeta(meta);
console.log(`✓ 二进制就位: ${dest}  (来自 ${basename(src)}, ${(meta.sizeBytes / 1048576).toFixed(1)} MB)`);
