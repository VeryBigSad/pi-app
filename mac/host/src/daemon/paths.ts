import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { mkdirSync, lstatSync, chmodSync } from "node:fs";
import { SecurityError } from "../security/security-error.js";

export interface HostPathLayout {
  readonly dataDirectory: string;
  readonly runtimeDirectory: string;
  readonly blobDirectory: string;
  readonly keyDirectory: string;
  readonly relayDirectory: string;
  readonly terminalRuntimeParent: string;
  readonly adminSocketPath: string;
  readonly approvalSocketPath: string;
  readonly journalPath: string;
  readonly revocationPath: string;
  readonly voiceLedgerPath: string;
  readonly hostStorePath: string;
  readonly configPath: string;
  readonly logDirectory: string;
}

const SOCKET_PATH_LIMIT_BYTES = 100;

export function defaultDataDirectory(): string {
  return join(homedir(), "Library", "Application Support", "PiMobile");
}

export function defaultLogDirectory(): string {
  return join(homedir(), "Library", "Logs", "PiMobile");
}

export function createPathLayout(dataDirectory?: string, logDirectory?: string): HostPathLayout {
  const data = resolve(dataDirectory ?? defaultDataDirectory());
  const logs = resolve(logDirectory ?? defaultLogDirectory());
  const layout: HostPathLayout = {
    dataDirectory: data,
    runtimeDirectory: join(data, "run"),
    blobDirectory: join(data, "blobs"),
    keyDirectory: join(data, "keys"),
    relayDirectory: join(data, "relay"),
    terminalRuntimeParent: join(data, "run"),
    adminSocketPath: join(data, "run", "host-admin.sock"),
    approvalSocketPath: join(data, "run", "approval.sock"),
    journalPath: join(data, "journal.sqlite"),
    revocationPath: join(data, "revocations.sqlite"),
    voiceLedgerPath: join(data, "voice-ledger.sqlite"),
    hostStorePath: join(data, "host-store.json"),
    configPath: join(data, "config.json"),
    logDirectory: logs,
  };
  if (Buffer.byteLength(layout.adminSocketPath, "utf8") > SOCKET_PATH_LIMIT_BYTES) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "admin socket path exceeds platform limits");
  }
  if (Buffer.byteLength(layout.approvalSocketPath, "utf8") > SOCKET_PATH_LIMIT_BYTES) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "approval socket path exceeds platform limits");
  }
  return layout;
}

export function ensurePrivateDirectories(layout: HostPathLayout): void {
  for (const directory of [
    layout.dataDirectory,
    layout.runtimeDirectory,
    layout.blobDirectory,
    layout.keyDirectory,
    layout.relayDirectory,
  ]) {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    const metadata = lstatSync(directory);
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
      throw new SecurityError("SECURITY_KEY_STORAGE_FAILED", "daemon directory is not a real directory");
    }
    if ((metadata.mode & 0o777) !== 0o700) chmodSync(directory, 0o700);
  }
  mkdirSync(layout.logDirectory, { recursive: true, mode: 0o700 });
}
