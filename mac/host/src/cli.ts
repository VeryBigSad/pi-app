#!/usr/bin/env node
import { constants } from "node:fs";
import { access, rm, stat } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline/promises";
import { PINNED_PI_VERSION, defaultSourceRoot, defaultTargetRoot, installPinnedPi, verifyPinnedPi } from "@pimobile/pi-patch";
import { PROTOCOL_MAJOR } from "@pimobile/protocol";
import { HostDaemon, type DaemonStatus } from "./daemon/daemon.js";
import { adminCall } from "./daemon/admin-server.js";
import { installLaunchdAgent, uninstallLaunchdAgent } from "./daemon/launchd.js";
import { createPathLayout, ensurePrivateDirectories } from "./daemon/paths.js";

const USAGE = `pi-mobile-host — Pi Mobile Mac host daemon

Usage: pi-mobile-host <command> [options]

Commands:
  install     Provision the pinned Pi runtime and install the launchd agent
  uninstall   Remove the launchd agent (keeps data unless --purge)
  verify      Verify pinned Pi integrity and data directory permissions
  pair        Open a pairing invitation, show QR + short code, confirm locally
  serve       Run the daemon in the foreground (this is what launchd starts)
  revoke      Revoke a paired device by device id
  devices     List paired devices
  status      Show daemon status

Options:
  --data-dir <path>   Override the data directory (default: ~/Library/Application Support/PiMobile)
  --json              Machine-readable output for status/verify/devices
  --yes               Non-interactive approval (pair)
  --purge             Also delete the data directory (uninstall)
  --no-load           Write the launchd plist without loading it (install)
  --help              Show this help
`;

interface ParsedArgs {
  readonly command: string | undefined;
  readonly dataDir: string | undefined;
  readonly json: boolean;
  readonly yes: boolean;
  readonly purge: boolean;
  readonly load: boolean;
  readonly help: boolean;
  readonly positional: readonly string[];
}

function parseArgs(argv: readonly string[]): ParsedArgs {
  const positional: string[] = [];
  let dataDir: string | undefined;
  let json = false;
  let yes = false;
  let purge = false;
  let load = true;
  let help = false;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    switch (argument) {
      case "--data-dir": {
        const value = argv[index + 1];
        if (value === undefined || value.startsWith("--")) throw new CliError("USAGE", "--data-dir requires a path");
        dataDir = value;
        index += 1;
        break;
      }
      case "--json": json = true; break;
      case "--yes": yes = true; break;
      case "--purge": purge = true; break;
      case "--no-load": load = false; break;
      case "--help": case "-h": help = true; break;
      default:
        if (argument?.startsWith("--") === true) throw new CliError("USAGE", `unknown option ${argument}`);
        if (argument !== undefined) positional.push(argument);
    }
  }
  return { command: positional.shift(), dataDir, json, yes, purge, load, help, positional };
}

class CliError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "CliError";
  }
}

function out(line: string): void {
  process.stdout.write(`${line}\n`);
}

function fail(error: unknown): never {
  if (error instanceof CliError) {
    process.stderr.write(`error[${error.code}]: ${error.message}\n`);
  } else if (error instanceof Error) {
    process.stderr.write(`error: ${error.message}\n`);
  } else {
    process.stderr.write("error: unknown failure\n");
  }
  process.exit(1);
}

async function commandInstall(parsed: ParsedArgs): Promise<void> {
  const layout = createPathLayout(parsed.dataDir);
  ensurePrivateDirectories(layout);
  out(`provisioning pinned Pi ${PINNED_PI_VERSION}…`);
  const sourceRoot = await defaultSourceRoot();
  const targetRoot = defaultTargetRoot();
  let installed = false;
  try {
    await verifyPinnedPi(targetRoot);
  } catch {
    await installPinnedPi(sourceRoot, targetRoot);
    installed = true;
  }
  await verifyPinnedPi(targetRoot);
  out(`pinned Pi ${PINNED_PI_VERSION} ${installed ? "installed" : "already present"} at ${targetRoot}`);
  const cliEntrypoint = fileURLToPath(import.meta.url);
  const agent = await installLaunchdAgent(layout, process.execPath, cliEntrypoint, { load: parsed.load });
  out(`launchd plist written to ${agent.plistPath}`);
  if (!parsed.load) {
    out("not loaded (--no-load); load with: launchctl bootstrap gui/$(id -u) " + agent.plistPath);
  } else if (agent.loaded) {
    out("launchd agent loaded; daemon will start at login and stay alive");
  } else {
    out(`launchd agent NOT loaded: ${agent.loadError ?? "unknown error"}`);
    out(`load manually: launchctl bootstrap gui/$(id -u) ${agent.plistPath}`);
  }
}

async function commandUninstall(parsed: ParsedArgs): Promise<void> {
  const layout = createPathLayout(parsed.dataDir);
  try {
    await adminCall(layout.adminSocketPath, { method: "stop" }, 5_000);
    out("running daemon asked to stop");
  } catch {
    // Not running; nothing to stop.
  }
  const agent = await uninstallLaunchdAgent();
  out(`launchd agent ${agent.unloaded ? "unloaded" : "was not loaded"}; plist removed from ${agent.plistPath}`);
  if (parsed.purge) {
    await rm(layout.dataDirectory, { recursive: true, force: true });
    await rm(defaultTargetRoot(), { recursive: true, force: true });
    out(`data directory ${layout.dataDirectory} and pinned Pi removed`);
  } else {
    out(`data kept at ${layout.dataDirectory} (use --purge to delete)`);
  }
}

async function commandVerify(parsed: ParsedArgs): Promise<void> {
  const layout = createPathLayout(parsed.dataDir);
  const checks: { name: string; ok: boolean; detail?: string }[] = [];
  let piOk = true;
  let piDetail = `pinned Pi ${PINNED_PI_VERSION} integrity verified`;
  try {
    await verifyPinnedPi(defaultTargetRoot());
  } catch (error) {
    piOk = false;
    piDetail = error instanceof Error ? error.message : "pinned Pi verification failed";
  }
  checks.push({ name: "pinned-pi", ok: piOk, detail: piDetail });
  for (const directory of [layout.dataDirectory, layout.keyDirectory]) {
    try {
      const metadata = await stat(directory);
      const ok = metadata.isDirectory() && (metadata.mode & 0o777) === 0o700;
      checks.push({ name: `dir:${directory}`, ok, ...(ok ? {} : { detail: "missing or permissions are not 0700" }) });
    } catch {
      checks.push({ name: `dir:${directory}`, ok: false, detail: "missing" });
    }
  }
  const preloadPath = createRequire(import.meta.url).resolve("@pimobile/preload");
  const preloadPresent = await access(preloadPath, constants.R_OK).then(() => true, () => false);
  checks.push({
    name: "preload",
    ok: preloadPresent,
    ...(preloadPresent ? {} : { detail: "policy preload bundle missing; run npm run build" }),
  });
  const ok = checks.every((check) => check.ok);
  if (parsed.json) {
    out(JSON.stringify({ ok, piVersion: PINNED_PI_VERSION, checks }, null, 2));
  } else {
    for (const check of checks) {
      out(`${check.ok ? "ok  " : "FAIL"} ${check.name}${check.detail === undefined ? "" : ` — ${check.detail}`}`);
    }
  }
  if (!ok) throw new CliError("VERIFY_FAILED", "integrity verification failed; reinstall with: pi-mobile-host install");
}

async function commandServe(parsed: ParsedArgs): Promise<void> {
  const daemon = new HostDaemon(parsed.dataDir);
  const shutdown = (signal: string): void => {
    out(`received ${signal}; shutting down`);
    void daemon.stop().then(() => process.exit(0), () => process.exit(1));
  };
  process.on("SIGINT", () => shutdown("SIGINT"));
  process.on("SIGTERM", () => shutdown("SIGTERM"));
  await daemon.start();
  const status = daemon.status();
  out(`pi-mobile-host ${status.version} serving (protocol ${String(PROTOCOL_MAJOR)}, pi ${status.piVersion}, direct :${String(status.listeners.directPort)}, pairing :${String(status.listeners.provisionalPort)})`);
}

async function commandStatus(parsed: ParsedArgs): Promise<void> {
  const layout = createPathLayout(parsed.dataDir);
  const result = await adminCall(layout.adminSocketPath, { method: "status" }).catch((error: unknown) => {
    if (error instanceof Error && error.message === "DAEMON_UNAVAILABLE") {
      throw new CliError("DAEMON_UNAVAILABLE", "daemon is not running; start it with: pi-mobile-host serve (or install the launchd agent)");
    }
    throw error;
  }) as DaemonStatus;
  if (parsed.json) {
    out(JSON.stringify(result, null, 2));
    return;
  }
  out(`version:    ${result.version} (protocol ${String(result.protocolMajor)}, manifest ${String(result.compatibilityManifest)})`);
  out(`pi:         ${result.piVersion}`);
  out(`uptime:     ${String(Math.round(result.uptimeMs / 1000))}s`);
  out(`listeners:  direct :${String(result.listeners.directPort)}, pairing :${String(result.listeners.provisionalPort)}`);
  out(`relay:      ${result.relay.state}${result.relay.routeId === undefined ? "" : ` (${result.relay.routeId})`}`);
  out(`sessions:   ${String(result.sessions.length)}${result.sessions.length === 0 ? "" : ` — ${result.sessions.join(", ")}`}`);
  out(`devices:    ${String(result.devices)}`);
  out(`terminal:   ${result.terminal.available ? "available" : `unavailable (${result.terminal.reason ?? "unknown"})`}`);
  out(`voice:      queue ${String(result.voice.queueSize)}`);
  out(`push:       ${result.push.configured ? `configured, ${String(result.push.published)} sent, ${String(result.push.failed)} failed` : "not configured"}`);
  out(`approvals:  ${String(result.pendingApprovals)} pending`);
}

interface PairBeginResult {
  readonly invitationId: string;
  readonly nonce: string;
  readonly expiresAtMs: number;
  readonly provisionalPort: number;
  readonly serverCertificateSha256: string;
  readonly relayRouteId: string | null;
  readonly uri: string;
  readonly invitation: unknown;
}

interface PairStatusResult {
  readonly state: string;
  readonly shortCode?: string;
  readonly deviceId?: string;
}

async function renderQr(payload: string): Promise<string> {
  const qrcode = await import("qrcode");
  return await qrcode.toString(payload, { type: "terminal", small: true, errorCorrectionLevel: "M" });
}

async function commandPair(parsed: ParsedArgs): Promise<void> {
  const layout = createPathLayout(parsed.dataDir);
  const admin = (method: string, params?: Record<string, unknown>): Promise<unknown> =>
    adminCall(layout.adminSocketPath, { method, ...(params === undefined ? {} : { params }) }).catch((error: unknown) => {
      if (error instanceof Error && error.message === "DAEMON_UNAVAILABLE") {
        throw new CliError("DAEMON_UNAVAILABLE", "daemon is not running; start it with: pi-mobile-host serve");
      }
      throw error;
    });
  const begin = (await admin("pair.begin")) as PairBeginResult;
  out("scan this QR with the Pi Mobile app to pair:");
  out(await renderQr(begin.uri));
  out(`pairing invitation: ${begin.uri}`);
  out(`invitation expires at ${new Date(begin.expiresAtMs).toISOString()}; waiting for the device…`);

  const deadline = begin.expiresAtMs;
  let announcedCode: string | undefined;
  for (;;) {
    if (Date.now() >= deadline) throw new CliError("SECURITY_CEREMONY_EXPIRED", "pairing invitation expired");
    const status = (await admin("pair.status")) as PairStatusResult;
    if (status.state === "issued") {
      out(`paired: ${status.deviceId ?? "unknown device"}`);
      return;
    }
    if (status.state === "failed") throw new CliError("SECURITY_CEREMONY_INVALID", "pairing failed or was rejected");
    if (status.state === "awaiting_local_confirmation" && status.shortCode !== undefined) {
      if (announcedCode !== status.shortCode) {
        announcedCode = status.shortCode;
        out(`short code: ${status.shortCode}`);
      }
      const approved = parsed.yes || await promptApproval(status.shortCode);
      const confirmed = (await admin("pair.confirm", { approved })) as PairStatusResult;
      if (!approved) throw new CliError("SECURITY_CEREMONY_INVALID", "pairing rejected locally");
      if (confirmed.state === "issued") {
        out(`paired: ${confirmed.deviceId ?? "unknown device"}`);
        return;
      }
    }
    await new Promise((resolveWait) => setTimeout(resolveWait, 500));
  }
}

async function promptApproval(shortCode: string): Promise<boolean> {
  if (!process.stdin.isTTY) {
    throw new CliError("USAGE", `device is waiting for confirmation (short code ${shortCode}); re-run with --yes to approve non-interactively`);
  }
  const readline = createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = await readline.question(`approve pairing for short code ${shortCode}? [y/N] `);
    return answer.trim().toLowerCase() === "y" || answer.trim().toLowerCase() === "yes";
  } finally {
    readline.close();
  }
}

async function commandRevoke(parsed: ParsedArgs): Promise<void> {
  const deviceId = parsed.positional[0];
  if (deviceId === undefined) throw new CliError("USAGE", "revoke requires a device id (see: pi-mobile-host devices)");
  const layout = createPathLayout(parsed.dataDir);
  await adminCall(layout.adminSocketPath, { method: "devices.revoke", params: { deviceId } });
  out(`revoked ${deviceId}`);
}

async function commandDevices(parsed: ParsedArgs): Promise<void> {
  const layout = createPathLayout(parsed.dataDir);
  const devices = (await adminCall(layout.adminSocketPath, { method: "devices.list" })) as readonly {
    deviceId: string;
    certificateId: string;
    deviceRouteKeyId?: string;
    createdAtMs: number;
  }[];
  if (parsed.json) {
    out(JSON.stringify(devices, null, 2));
    return;
  }
  if (devices.length === 0) {
    out("no paired devices");
    return;
  }
  for (const device of devices) {
    out(`${device.deviceId}  paired ${new Date(device.createdAtMs).toISOString()}  cert ${device.certificateId.slice(0, 16)}…`);
  }
}

async function main(): Promise<void> {
  const parsed = parseArgs(process.argv.slice(2));
  if (parsed.help || parsed.command === undefined) {
    process.stdout.write(USAGE);
    if (parsed.command === undefined && !parsed.help) throw new CliError("USAGE", "missing command");
    return;
  }
  switch (parsed.command) {
    case "install": return await commandInstall(parsed);
    case "uninstall": return await commandUninstall(parsed);
    case "verify": return await commandVerify(parsed);
    case "serve": return await commandServe(parsed);
    case "status": return await commandStatus(parsed);
    case "pair": return await commandPair(parsed);
    case "revoke": return await commandRevoke(parsed);
    case "devices": return await commandDevices(parsed);
    default: throw new CliError("USAGE", `unknown command ${parsed.command}`);
  }
}

main().catch(fail);
