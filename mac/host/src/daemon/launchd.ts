import { execFile } from "node:child_process";
import { homedir, userInfo } from "node:os";
import { join } from "node:path";
import { chmod, mkdir, readFile, unlink, writeFile } from "node:fs/promises";
import { promisify } from "node:util";
import type { HostPathLayout } from "./paths.js";

const execFileAsync = promisify(execFile);

export const LAUNCHD_LABEL = "io.github.verybigsad.pimobile.host";

export interface LaunchdInstallResult {
  readonly plistPath: string;
  readonly loaded: boolean;
  readonly loadError?: string;
}

function escapeXml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

export function launchAgentsDirectory(): string {
  return join(homedir(), "Library", "LaunchAgents");
}

export function launchdPlistPath(): string {
  return join(launchAgentsDirectory(), `${LAUNCHD_LABEL}.plist`);
}

/** Renders the launchd agent plist for Apple Silicon macOS 14+ (gui domain, KeepAlive). */
export function renderLaunchdPlist(layout: HostPathLayout, nodeExecutable: string, cliEntrypoint: string): string {
  const programArguments = [nodeExecutable, cliEntrypoint, "serve", "--data-dir", layout.dataDirectory]
    .map((argument) => `        <string>${escapeXml(argument)}</string>`)
    .join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
  <dict>
    <key>Label</key>
    <string>${LAUNCHD_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
${programArguments}
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
      <key>SuccessfulExit</key>
      <false/>
      <key>Crashed</key>
      <true/>
    </dict>
    <key>ThrottleInterval</key>
    <integer>10</integer>
    <key>ProcessType</key>
    <string>Background</string>
    <key>LimitLoadToSessionType</key>
    <string>Aqua</string>
    <key>StandardOutPath</key>
    <string>${escapeXml(join(layout.logDirectory, "daemon.out.log"))}</string>
    <key>StandardErrorPath</key>
    <string>${escapeXml(join(layout.logDirectory, "daemon.err.log"))}</string>
    <key>EnvironmentVariables</key>
    <dict>
      <key>PATH</key>
      <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    </dict>
  </dict>
</plist>
`;
}

async function runLaunchctl(args: readonly string[]): Promise<{ exitCode: number; stderr: string }> {
  try {
    await execFileAsync("launchctl", [...args], { timeout: 15_000 });
    return { exitCode: 0, stderr: "" };
  } catch (error) {
    if (error instanceof Error && "code" in error && typeof error.code === "number") {
      const stderr = "stderr" in error && typeof error.stderr === "string" ? error.stderr : "";
      return { exitCode: error.code, stderr };
    }
    return { exitCode: -1, stderr: error instanceof Error ? error.message : "launchctl failed" };
  }
}

/** Writes the plist (mode 0644) and bootstraps the agent into the gui domain. */
export async function installLaunchdAgent(
  layout: HostPathLayout,
  nodeExecutable: string,
  cliEntrypoint: string,
  options: { readonly load?: boolean } = {},
): Promise<LaunchdInstallResult> {
  await mkdir(launchAgentsDirectory(), { recursive: true });
  await mkdir(layout.logDirectory, { recursive: true, mode: 0o700 });
  const plistPath = launchdPlistPath();
  const plist = renderLaunchdPlist(layout, nodeExecutable, cliEntrypoint);
  const existing = await readFile(plistPath, "utf8").catch(() => undefined);
  const domain = `gui/${String(userInfo().uid)}`;
  if (existing !== undefined && existing !== plist) {
    await runLaunchctl(["bootout", `${domain}/${LAUNCHD_LABEL}`]);
  }
  await writeFile(plistPath, plist, { mode: 0o644 });
  await chmod(plistPath, 0o644);
  if (options.load === false) return { plistPath, loaded: false };
  const result = await runLaunchctl(["bootstrap", domain, plistPath]);
  if (result.exitCode === 0) return { plistPath, loaded: true };
  if (result.stderr.includes("service already loaded") || result.stderr.includes("Bootstrap failed: 17")) {
    return { plistPath, loaded: true };
  }
  return { plistPath, loaded: false, loadError: result.stderr.trim() || `launchctl exit ${String(result.exitCode)}` };
}

/** Bootouts the agent (best effort) and removes the plist. */
export async function uninstallLaunchdAgent(): Promise<{ plistPath: string; unloaded: boolean }> {
  const plistPath = launchdPlistPath();
  const domain = `gui/${String(userInfo().uid)}`;
  const result = await runLaunchctl(["bootout", `${domain}/${LAUNCHD_LABEL}`]);
  await unlink(plistPath).catch(() => undefined);
  return { plistPath, unloaded: result.exitCode === 0 };
}
