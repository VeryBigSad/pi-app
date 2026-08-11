import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import {
  AGENT_SESSION_FILE,
  BASH_TOOL_FILE,
  PINNED_AGENT_SESSION_SHA256,
  PINNED_BASH_TOOL_SHA256,
  POLICY_HOOK_KEY,
  PINNED_PACKAGE_SHA256,
  PINNED_PATCHED_AGENT_SESSION_SHA256,
  PINNED_PATCHED_BASH_TOOL_SHA256,
  defaultSourceRoot,
  generateDistManifest,
  patchAgentSession,
  patchBashTool,
  pinnedDistManifest,
} from "../src/index.js";

const sha256 = (value: string): string => createHash("sha256").update(value).digest("hex");

describe("pinned Pi agent-session patch", () => {
  it("matches exactly one audited Pi 0.84 executeBash body", async () => {
    const source = await readFile(`${await defaultSourceRoot()}/${AGENT_SESSION_FILE}`, "utf8");
    expect(sha256(source)).toBe(PINNED_AGENT_SESSION_SHA256);
    const patched = patchAgentSession(source);
    expect(sha256(patched)).toBe(PINNED_PATCHED_AGENT_SESSION_SHA256);
    expect(patched).toContain(`Symbol.for("${POLICY_HOOK_KEY}")`);
    expect(patched).toContain("command: resolvedCommand, cwd, signal: abortController.signal");
    expect(patched.match(/await piMobilePolicyHook\(/gu)).toHaveLength(2);
    expect(patchAgentSession(patched)).toBe(patched);
  });

  it("refuses drift rather than applying a partial patch", () => {
    expect(() => patchAgentSession("async executeBash() {}")).toThrow("does not match");
  });
});

describe("pinned Pi bash tool patch", () => {
  it("hooks the tool execute path once with the final prefixed command and fails closed", async () => {
    const source = await readFile(`${await defaultSourceRoot()}/${BASH_TOOL_FILE}`, "utf8");
    expect(sha256(source)).toBe(PINNED_BASH_TOOL_SHA256);
    const patched = patchBashTool(source);
    expect(sha256(patched)).toBe(PINNED_PATCHED_BASH_TOOL_SHA256);
    expect(patched).toContain(`Symbol.for("${POLICY_HOOK_KEY}")`);
    expect(patched).toContain('throw new Error("Pi Mobile policy hook unavailable")');
    expect(patched.match(/await piMobilePolicyHook\(/gu)).toHaveLength(1);
    const hookCall = 'await piMobilePolicyHook(Object.freeze({ version: 1, kind: "bash", operationId: _toolCallId, command: resolvedCommand, cwd, signal: signal ?? new AbortController().signal }));';
    expect(patched).toContain(hookCall);
    expect(patched.indexOf(hookCall)).toBeLessThan(patched.indexOf("resolveSpawnContext(resolvedCommand"));
    expect(patched.indexOf(hookCall)).toBeGreaterThan(patched.indexOf("const resolvedCommand = commandPrefix"));
    expect(patchBashTool(patched)).toBe(patched);
  });

  it("refuses drift rather than applying a partial patch", () => {
    expect(() => patchBashTool("async execute() {}")).toThrow("does not match");
  });
});

describe("pinned Pi dist manifest", () => {
  it("is sorted, complete, and consistent with the pinned constants", async () => {
    const manifest = await pinnedDistManifest();
    expect(manifest.piVersion).toBe("0.84.0");
    expect(manifest.policyHookKey).toBe(POLICY_HOOK_KEY);
    expect(manifest.files["package.json"]).toBe(PINNED_PACKAGE_SHA256);
    expect(manifest.files[AGENT_SESSION_FILE]).toBe(PINNED_AGENT_SESSION_SHA256);
    expect(manifest.files[BASH_TOOL_FILE]).toBe(PINNED_BASH_TOOL_SHA256);
    expect(manifest.patched).toEqual({
      [AGENT_SESSION_FILE]: PINNED_PATCHED_AGENT_SESSION_SHA256,
      [BASH_TOOL_FILE]: PINNED_PATCHED_BASH_TOOL_SHA256,
    });
    const keys = Object.keys(manifest.files);
    expect(keys.length).toBeGreaterThan(100);
    expect([...keys].sort((left, right) => left.localeCompare(right))).toEqual(keys);
  });

  it("regenerates deterministically from the pristine installed package", async () => {
    const manifest = await pinnedDistManifest();
    const regenerated = await generateDistManifest(await defaultSourceRoot());
    expect(JSON.stringify(regenerated, null, 2)).toBe(JSON.stringify(manifest, null, 2));
  });
});
