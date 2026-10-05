#!/usr/bin/env bun
/**
 * 交叉构建 arm64 Debian 12 rootfs，导出为 tar.gz + sha256 + manifest。
 *
 * 依赖：podman（本机已装，走 qemu binfmt 跑 linux/arm64）。
 *
 * 用法：
 *   bun scripts/build-rootfs.ts                 # 完整构建（要求 overlays/opt/narrafork 有二进制）
 *   bun scripts/build-rootfs.ts --skip-binary   # 不强制校验二进制（纯环境包）
 *   bun scripts/build-rootfs.ts --dry-run       # 只打印计划，不落盘
 */
import { createHash } from "node:crypto";
import { createReadStream, existsSync, mkdirSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dir, "..");
const pkg = JSON.parse(await Bun.file(join(ROOT, "package.json")).text()) as { version?: string };
const VERSION = pkg.version ?? "0.1.0";
const DIST = join(ROOT, "dist");
const IMAGE = `narrafork-android-rootfs:${VERSION}`;
const CONTAINER = `nf-android-export-${VERSION.replaceAll(/[^A-Za-z0-9_.-]+/g, "-")}`;
const BUNDLE = join(DIST, `narrafork-android-rootfs-${VERSION}-arm64.tar.gz`);
const SHA_FILE = `${BUNDLE}.sha256`;
const MANIFEST = join(DIST, `narrafork-android-rootfs-${VERSION}-arm64.json`);
const BIN_DIR = join(ROOT, "rootfs", "overlays", "opt", "narrafork");

const flag = (n: string) => process.argv.includes(`--${n}`);
const dryRun = flag("dry-run");
const skipBinary = flag("skip-binary");

function run(cmd: string[], opts: { allowFailure?: boolean } = {}) {
	console.log(`$ ${cmd.join(" ")}`);
	const r = Bun.spawnSync(cmd, { cwd: ROOT, stdout: "pipe", stderr: "pipe" });
	const out = new TextDecoder().decode(r.stdout).trim();
	const err = new TextDecoder().decode(r.stderr).trim();
	if (r.exitCode !== 0 && !opts.allowFailure) {
		if (out) console.log(out);
		console.error(err);
		throw new Error(`命令失败(${r.exitCode}): ${cmd.join(" ")}`);
	}
	return { out, err, code: r.exitCode ?? 1 };
}

async function sha256(path: string): Promise<string> {
	const hash = createHash("sha256");
	for await (const chunk of createReadStream(path)) hash.update(chunk as Buffer);
	return hash.digest("hex");
}

const binaries = existsSync(BIN_DIR) ? readdirSync(BIN_DIR).filter((f) => !f.endsWith(".json")) : [];
if (!skipBinary && binaries.length === 0) {
	console.error(`❌ ${BIN_DIR} 下没有 narrafork 二进制。先运行 bun scripts/build-binary.ts（或加 --skip-binary）。`);
	process.exit(1);
}

console.log("→ rootfs 构建计划");
console.log(`  version : ${VERSION}`);
console.log(`  image   : ${IMAGE}`);
console.log(`  bundle  : ${BUNDLE}`);
console.log(`  binary  : ${skipBinary ? "(跳过校验)" : binaries.join(", ") || "(无)"}`);

if (dryRun) {
	console.log("✓ dry-run，未改动任何文件。");
	process.exit(0);
}

if (!Bun.which("podman")) {
	console.error("❌ 需要 podman（本机交叉构建 linux/arm64 依赖它 + qemu binfmt）。");
	process.exit(1);
}

mkdirSync(DIST, { recursive: true });

// 1. 构建镜像（含 overlays 与二进制）
run(["podman", "build", "--platform", "linux/arm64", "-t", IMAGE, "rootfs"]);

// 2. 从镜像创建容器并导出扁平 rootfs，管道进 gzip（避免把整包读进内存）。
run(["podman", "rm", "-f", CONTAINER], { allowFailure: true });
run(["podman", "create", "--name", CONTAINER, "--arch", "arm64", IMAGE, "true"]);

console.log(`→ 导出并压缩 → ${BUNDLE}`);
{
	const exporter = Bun.spawn(["podman", "export", CONTAINER], { stdout: "pipe" });
	const gz = Bun.spawn(["gzip", "-9", "-c"], { stdin: exporter.stdout, stdout: Bun.file(BUNDLE) });
	const [exCode, gzCode] = await Promise.all([exporter.exited, gz.exited]);
	if (exCode !== 0 || gzCode !== 0) throw new Error(`导出/压缩失败 export=${exCode} gzip=${gzCode}`);
}
run(["podman", "rm", "-f", CONTAINER], { allowFailure: true });

// 3. sha256 + manifest
const digest = await sha256(BUNDLE);
writeFileSync(SHA_FILE, `${digest}  ${BUNDLE.split("/").pop()}\n`);

const manifest = {
	name: "narrafork-android-rootfs",
	version: VERSION,
	architecture: "arm64",
	debian: "bookworm (12)",
	generatedAt: new Date().toISOString(),
	bundle: BUNDLE.split("/").pop(),
	sha256: digest,
	sizeBytes: statSync(BUNDLE).size,
	sha256File: `${BUNDLE.split("/").pop()}.sha256`,
	includedBinaries: binaries,
	packages: ["ca-certificates", "git", "bash", "dtach", "ripgrep", "sqlite3", "procps", "curl", "less", "tar", "xz-utils", "grep"],
	tools: ["narrafork-start", "narrafork-mirror"],
	runtime: { android: true, proot: true, defaultHost: "0.0.0.0", defaultPort: 7778, localContainers: false },
};
writeFileSync(MANIFEST, `${JSON.stringify(manifest, null, "\t")}\n`);

console.log(`✓ ${BUNDLE}  (${(manifest.sizeBytes / 1048576).toFixed(1)} MB)`);
console.log(`✓ ${SHA_FILE}`);
console.log(`✓ ${MANIFEST}`);
console.log("\n下一步：bash tests/verify-rootfs.sh 解包冒烟。");
