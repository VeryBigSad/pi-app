import { execFileSync } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { LAUNCHD_LABEL, renderLaunchdPlist } from "../src/daemon/launchd.js";
import { createPathLayout } from "../src/daemon/paths.js";

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

describe("renderLaunchdPlist", () => {
  it("renders a plist that plutil accepts, wired to serve the data directory", async () => {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-launchd-"));
    roots.push(root);
    const layout = createPathLayout(join(root, "data"), join(root, "logs"));
    const plist = renderLaunchdPlist(layout, "/opt/homebrew/bin/node", "/opt/pi-mobile/cli.js");
    expect(plist).toContain(LAUNCHD_LABEL);
    expect(plist).toContain("serve");
    expect(plist).toContain("--data-dir");
    expect(plist).toContain(join(root, "data"));
    expect(plist).toContain("<key>RunAtLoad</key>");
    expect(plist).toContain("<key>KeepAlive</key>");
    expect(plist).toContain(join(root, "logs", "daemon.out.log"));
    const plistPath = join(root, "agent.plist");
    await writeFile(plistPath, plist);
    if (process.platform === "darwin") {
      expect(execFileSync("plutil", ["-lint", plistPath], { encoding: "utf8" })).toContain("OK");
    } else {
      // plutil is macOS-only; on CI Linux fall back to structural checks.
      expect(plist.trim().startsWith("<?xml")).toBe(true);
      expect(plist.trim().endsWith("</plist>")).toBe(true);
    }
  });

  it("escapes XML special characters in paths", () => {
    const layout = createPathLayout("/tmp/pi mobile & <friends>");
    const plist = renderLaunchdPlist(layout, "/opt/homebrew/bin/node", "/tmp/cli.js");
    expect(plist).toContain("&amp;");
    expect(plist).toContain("&lt;friends&gt;");
    expect(plist).not.toContain("& <friends>");
  });
});
