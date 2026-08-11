import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { promisify } from "node:util";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import {
  MAX_HISTORY_BYTES,
  UINT64_MAX,
  decodeBase64,
  decodeTerminalPacket,
  encodeBase64,
  encodeTerminalPacket,
  parseNativeCommand,
  parseUint64,
} from "../android/terminal/web/src/protocol.ts";

const execute = promisify(execFile);
const root = resolve(import.meta.dirname, "..");
const manifestPath = resolve(root, "android/terminal/web/asset-manifest.json");
const assetDirectory = resolve(root, "android/terminal/src/main/assets/terminal");

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function readManifest() {
  return JSON.parse(await readFile(manifestPath, "utf8"));
}

async function assetSnapshot() {
  const manifest = await readManifest();
  const files = Object.fromEntries(await Promise.all(Object.keys(manifest.files).map(async (name) => {
    const bytes = await readFile(resolve(assetDirectory, name));
    return [name, sha256(bytes)];
  })));
  return { manifest, files };
}

describe("terminal assets", () => {
  it("build deterministically twice", async () => {
    await execute(process.execPath, [resolve(root, "scripts/build-terminal.mjs")], { cwd: root });
    const first = await assetSnapshot();
    await execute(process.execPath, [resolve(root, "scripts/build-terminal.mjs")], { cwd: root });
    const second = await assetSnapshot();
    expect(second).toEqual(first);
  });

  it("match the checked manifest and pinned xterm source", async () => {
    const manifest = await readManifest();
    const lock = JSON.parse(await readFile(resolve(root, "package-lock.json"), "utf8"));
    const installed = JSON.parse(await readFile(resolve(root, "node_modules/@xterm/xterm/package.json"), "utf8"));
    expect(manifest.target).toBe("chrome91");
    expect(manifest.xtermVersion).toBe("6.1.0-beta.292");
    expect(manifest.xtermIntegrity).toBe("sha512-17zqK5tM/l6qeD7McF42OrEJ6w3XqJ2vFVKdWqu0cYLzdFqMWAHp2oFNc8Fj5DmqDSl1E1FZEg6IFflDllTvLA==");
    expect(manifest.xtermPackedSha256).toBe("66cd04723b96a17ce85027f3f9480d4398a134db1f5cd359784a01dbc2c05510");
    expect(lock.packages["node_modules/@xterm/xterm"].version).toBe(manifest.xtermVersion);
    expect(lock.packages["node_modules/@xterm/xterm"].integrity).toBe(manifest.xtermIntegrity);
    expect(installed.version).toBe(manifest.xtermVersion);
    for (const [name, expected] of Object.entries(manifest.files)) {
      const bytes = await readFile(resolve(assetDirectory, name));
      expect(sha256(bytes)).toBe(expected);
    }
    const shim = await readFile(resolve(root, manifest.structuredCloneShim.path));
    const source = await readFile(resolve(root, manifest.structuredCloneShim.upstreamSourceLocator.path));
    expect(sha256(shim)).toBe(manifest.structuredCloneShim.sha256);
    expect(sha256(source)).toBe(manifest.structuredCloneShim.upstreamSourceLocator.sha256);
    expect(source.toString("utf8").match(/structuredClone\(/g)).toHaveLength(4);
    expect(shim.toString("utf8")).not.toContain("JSON.stringify");
  });

  it("loads only local scripts and styles under a network-denying CSP", async () => {
    const html = await readFile(resolve(root, "android/terminal/web/index.html"), "utf8");
    expect([...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map((match) => match[1])).toEqual(["terminal.js"]);
    expect([...html.matchAll(/<link[^>]+href="([^"]+)"/g)].map((match) => match[1])).toEqual(["xterm.css", "terminal-shell.css"]);
    expect(html).toContain("default-src 'none'");
    expect(html).toContain("connect-src 'none'");
    expect(html).toContain("frame-src 'none'");
    expect(html).not.toMatch(/(?:src|href)="(?:https?:)?\/\//);
    const runtime = await readFile(resolve(root, "android/terminal/src/main/kotlin/io/github/verybigsad/pimobile/terminal/TerminalRuntime.kt"), "utf8");
    expect(runtime).not.toContain("addJavascriptInterface");
    expect(runtime).toContain("setOf(TerminalAssetOrigin)");
    expect(runtime).toContain("blockNetworkLoads = true");
  });

  it("retains xterm IME, key-release, focus, paste, and resize paths", async () => {
    const browserTerminal = await readFile(resolve(root, "node_modules/@xterm/xterm/src/browser/CoreBrowserTerminal.ts"), "utf8");
    const keyboardService = await readFile(resolve(root, "node_modules/@xterm/xterm/src/browser/services/KeyboardService.ts"), "utf8");
    const kittyKeyboard = await readFile(resolve(root, "node_modules/@xterm/xterm/src/common/input/KittyKeyboard.ts"), "utf8");
    const runtime = await readFile(resolve(root, "android/terminal/web/src/terminal.ts"), "utf8");
    expect(browserTerminal).toContain("'compositionstart'");
    expect(browserTerminal).toContain("'compositionend'");
    expect(browserTerminal).toContain("'keyup'");
    expect(browserTerminal).toContain("'keydown'");
    expect(keyboardService).toContain("KittyKeyboardEventType.RELEASE");
    expect(kittyKeyboard).toContain("REPORT_EVENT_TYPES");
    expect(runtime).toContain("terminal.paste");
    expect(runtime).toContain("new ResizeObserver");
    expect(runtime).toContain('inputmode", "text"');
    expect(runtime).toContain('addEventListener("focus"');
  });

  it("installs the structuredClone shim synchronously before the canary probe", async () => {
    const runtime = await readFile(resolve(root, "android/terminal/web/src/terminal.ts"), "utf8");
    const shim = await readFile(resolve(root, "android/terminal/web/src/compat.ts"), "utf8");
    const bundle = await readFile(resolve(assetDirectory, "terminal.js"), "utf8");
    // The compat module must be the first import so esbuild emits it ahead of the canary.
    expect(runtime.trimStart().startsWith('import { installedNarrowStructuredClone } from "./compat.js";')).toBe(true);
    // Shim installs at module scope, before boot() and the canary run.
    expect(shim).toContain("globalThis.structuredClone =");
    expect(runtime.indexOf("installedNarrowStructuredClone")).toBeLessThan(runtime.indexOf("cloneCanary()"));
    // In the bundled (minified) IIFE the shim marker must precede the canary report marker.
    const shimMarker = bundle.indexOf("DataCloneError");
    const canaryMarker = bundle.indexOf("TERMINAL_RUNTIME_CANARY_FAILED");
    expect(shimMarker).toBeGreaterThanOrEqual(0);
    expect(canaryMarker).toBeGreaterThanOrEqual(0);
    expect(shimMarker).toBeLessThan(canaryMarker);
  });

  it("accepts either native structuredClone or the functioning shim in the clone canary", async () => {
    const runtime = await readFile(resolve(root, "android/terminal/web/src/terminal.ts"), "utf8");
    const cloneCanary = runtime.slice(runtime.indexOf("function cloneCanary"), runtime.indexOf("function boot"));
    // Shim path: non-plain objects and cycles must be rejected.
    expect(cloneCanary).toContain("if (installedNarrowStructuredClone)");
    // Native path: cycles must clone into a self-referential deep copy.
    expect(cloneCanary).toContain("cloned.self === cloned");
    // The old shim-only expectation (cycles always throw) must be gone.
    expect(cloneCanary).not.toContain("rejectedCycle");
  });

  it("keeps readiness fail-closed on canary failure in JS and native", async () => {
    const runtime = await readFile(resolve(root, "android/terminal/web/src/terminal.ts"), "utf8");
    const native = await readFile(resolve(root, "android/terminal/src/main/kotlin/io/github/verybigsad/pimobile/terminal/TerminalRuntime.kt"), "utf8");
    const readyReports = [...runtime.matchAll(/report\(\{[^}]*type: "terminal\.ready"[^}]*\}\)/g)].map((match) => match[0]);
    expect(readyReports).toEqual(['report({ type: "terminal.ready", canaryOk: true })']);
    expect(runtime).toContain("canaryPassed = true");
    expect(runtime).toContain("if (!canaryPassed) break;");
    expect(runtime).toContain("forceCanaryFailure");
    expect(runtime).toContain("if (!ok) {");
    expect(native).toContain('optBoolean("canaryOk", false)');
    expect(native).toContain("TERMINAL_READY_WITHOUT_CANARY");
    expect(native).toContain("canaryPassed = result.compatible");
    expect(native).toContain("$TerminalAssetUrl?forceCanaryFailure=1");
  });

  it("keeps arbitrary terminal bytes with full uint64 generation and sequence", () => {
    const bytes = Uint8Array.of(0, 255, 0xc3, 0x28, 0x1b, 0x5b, 0x41);
    const encoded = encodeTerminalPacket(UINT64_MAX, UINT64_MAX, bytes);
    const decoded = decodeTerminalPacket(encoded);
    expect(decoded?.generation).toBe(UINT64_MAX);
    expect(decoded?.sequence).toBe(UINT64_MAX);
    expect(decoded?.bytes).toEqual(bytes);
    expect(decodeBase64(encodeBase64(bytes))).toEqual(bytes);
    expect(parseUint64(UINT64_MAX.toString())).toBe(UINT64_MAX);
    expect(parseUint64("18446744073709551616")).toBeUndefined();
    expect(parseUint64("01")).toBeUndefined();
    expect(parseNativeCommand(JSON.stringify({
      type: "terminal.output",
      generation: UINT64_MAX.toString(),
      sequence: UINT64_MAX.toString(),
      bytes: encodeBase64(bytes),
    }))).toEqual({ type: "terminal.output", generation: UINT64_MAX, sequence: UINT64_MAX, bytes });
  });

  it("validates history bounds and honest restore commands", () => {
    expect(parseNativeCommand(JSON.stringify({
      type: "terminal.history",
      generation: "9",
      capturedAt: "2026-08-09T00:00:00Z",
      text: "line one\nline two",
      truncatedLines: true,
      truncatedBytes: false,
    }))).toMatchObject({ type: "terminal.history", generation: 9n, truncatedLines: true });
    expect(parseNativeCommand(JSON.stringify({
      type: "terminal.history",
      generation: "9",
      capturedAt: "2026-08-09T00:00:00Z",
      text: "x".repeat(MAX_HISTORY_BYTES + 1),
      truncatedLines: false,
      truncatedBytes: false,
    }))).toBeUndefined();
    expect(parseNativeCommand(JSON.stringify({
      type: "terminal.restored",
      requiresReconnect: true,
      screenRestored: false,
      scrollbackRestored: false,
    }))).toEqual({
      type: "terminal.restored",
      requiresReconnect: true,
      screenRestored: false,
      scrollbackRestored: false,
    });
    expect(parseNativeCommand(JSON.stringify({
      type: "terminal.restored",
      requiresReconnect: false,
      screenRestored: true,
      scrollbackRestored: true,
    }))).toBeUndefined();
  });
});
