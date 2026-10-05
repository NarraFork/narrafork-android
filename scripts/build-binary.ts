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
 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from "node:fs";
import { basename, join, resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "..");
const MAIN_REPO = resolve(ROOT, "../narrafork");
const OUT_DIR = join(ROOT, "rootfs", "overlays", "opt", "narrafork");

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
const { chmodSync, writeFileSync } = await import("node:fs");
chmodSync(dest, 0o755);
// 注意：此文件会被 COPY 进发布的 rootfs 包，不得写入本机绝对路径等敏感信息。
const meta = { name: basename(src), sizeBytes: statSync(src).size, copiedAt: new Date().toISOString() };
writeFileSync(join(OUT_DIR, "binary-info.json"), `${JSON.stringify(meta, null, "\t")}\n`);
console.log(`✓ 二进制就位: ${dest}  (来自 ${basename(src)}, ${(meta.sizeBytes / 1048576).toFixed(1)} MB)`);
