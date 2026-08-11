#!/usr/bin/env node
import { createHash } from "node:crypto";
import { cp, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { build } from "esbuild";

const xtermVersion = "6.1.0-beta.292";
const xtermIntegrity = "sha512-17zqK5tM/l6qeD7McF42OrEJ6w3XqJ2vFVKdWqu0cYLzdFqMWAHp2oFNc8Fj5DmqDSl1E1FZEg6IFflDllTvLA==";
const xtermPackedSha256 = "66cd04723b96a17ce85027f3f9480d4398a134db1f5cd359784a01dbc2c05510";
const xtermCloneSourceSha256 = "aaf01c339fb7cb80f1f21e126633f708e74844e0c408b3143d11d5e6ad229e26";
const root = resolve(import.meta.dirname, "..");
const output = resolve(root, "android/terminal/src/main/assets/terminal");
const cloneSourcePath = "node_modules/@xterm/xterm/src/common/services/CoreService.ts";
const shimPath = "android/terminal/web/src/compat.ts";

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

const packageLock = JSON.parse(await readFile(resolve(root, "package-lock.json"), "utf8"));
const lockedXterm = packageLock.packages?.["node_modules/@xterm/xterm"];
if (lockedXterm?.version !== xtermVersion || lockedXterm?.integrity !== xtermIntegrity) {
  throw new Error("locked xterm version or integrity mismatch");
}
const installedPackage = JSON.parse(await readFile(resolve(root, "node_modules/@xterm/xterm/package.json"), "utf8"));
if (installedPackage.version !== xtermVersion) throw new Error("installed xterm version mismatch");
const cloneSource = await readFile(resolve(root, cloneSourcePath));
if (sha256(cloneSource) !== xtermCloneSourceSha256) throw new Error("xterm structuredClone source locator changed");
if ((cloneSource.toString("utf8").match(/structuredClone\(/g) ?? []).length !== 4) {
  throw new Error("xterm structuredClone call sites changed");
}

await mkdir(output, { recursive: true });
const buildResult = await build({
  absWorkingDir: root,
  entryPoints: ["android/terminal/web/src/terminal.ts"],
  outfile: resolve(output, "terminal.js"),
  bundle: true,
  format: "iife",
  platform: "browser",
  target: ["chrome91"],
  minify: true,
  sourcemap: false,
  legalComments: "none",
  charset: "utf8",
  treeShaking: true,
  metafile: true,
  logLevel: "warning",
});
if (!Object.keys(buildResult.metafile.inputs).some((name) => name.startsWith("node_modules/@xterm/xterm/"))) {
  throw new Error("xterm was not bundled");
}
await cp(resolve(root, "android/terminal/web/index.html"), resolve(output, "index.html"));
await cp(resolve(root, "android/terminal/web/terminal-shell.css"), resolve(output, "terminal-shell.css"));
await cp(resolve(root, "node_modules/@xterm/xterm/css/xterm.css"), resolve(output, "xterm.css"));
await cp(resolve(root, "node_modules/@xterm/xterm/LICENSE"), resolve(output, "XTERM-LICENSE"));

const files = ["terminal.js", "index.html", "terminal-shell.css", "xterm.css", "XTERM-LICENSE"];
const hashes = Object.fromEntries(await Promise.all(files.map(async (name) => {
  const bytes = await readFile(resolve(output, name));
  return [name, sha256(bytes)];
})));
const shimSource = await readFile(resolve(root, shimPath));
const manifest = {
  xtermVersion,
  xtermIntegrity,
  xtermPackedSha256,
  target: "chrome91",
  structuredCloneShim: {
    path: shimPath,
    sha256: sha256(shimSource),
    upstreamSourceLocator: {
      path: cloneSourcePath,
      sha256: xtermCloneSourceSha256,
      callSites: 4,
    },
  },
  files: hashes,
};
const manifestPath = resolve(root, "android/terminal/web/asset-manifest.json");
await mkdir(dirname(manifestPath), { recursive: true });
await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
