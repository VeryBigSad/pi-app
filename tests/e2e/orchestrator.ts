import { createHash, randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { createConnection } from "node:net";
import { access, chmod, mkdtemp, mkdir, open, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { inspectHostState, isolateHostState, restoreHostState, type E2eHostStateSnapshot } from "./host-state.js";

const APP_PACKAGE = "io.github.verybigsad.pimobile.debug";
const TEST_PACKAGE = `${APP_PACKAGE}.test`;
const TEST_RUNNER = `${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner`;
const INSTALLED_STACK_TESTS = [
  "io.github.verybigsad.pimobile.e2e.InstalledStackE2ETest",
  "io.github.verybigsad.pimobile.e2e.InstalledStackDeferredHooksTest",
].join(",");
const E2E_CHANNEL_PATH = /^\/data\/local\/tmp\/pi-mobile-e2e-([A-Za-z0-9_-]{43})\/config$/u;
const MAX_PROCESS_OUTPUT_BYTES = 16 << 20;
const MAX_ADMIN_BYTES = 64 << 10;
const MAX_CHANNEL_BYTES = 64 << 10;
const MAX_HOOK_EVIDENCE_BYTES = 4 << 10;
export const ALLOWED_E2E_AVD_NAMES = new Set([
  "PiApp_API_29",
  "PiApp_API_34_AOSP",
  "PiApp_API_34_AOSP_UI",
  "PiApp_API_36",
  "domonap",
]);
export const E2E_HOOKS = ["voice", "push", "external-push"] as const;
export type E2eHook = typeof E2E_HOOKS[number];

export class HarnessError extends Error {
  constructor(readonly code: string, options?: ErrorOptions) {
    super(code, options);
    this.name = "HarnessError";
  }
}

export interface ProcessResult {
  readonly code: number;
  readonly stdout: string;
  readonly stderr: string;
}

export interface ProcessOptions {
  readonly cwd?: string;
  readonly env?: NodeJS.ProcessEnv;
  readonly input?: Uint8Array;
  readonly timeoutMs?: number;
  readonly signal?: AbortSignal;
}

export interface ProcessRunner {
  run(command: string, args: readonly string[], options?: ProcessOptions): Promise<ProcessResult>;
}

export interface AdminClient {
  call(method: string, params?: Readonly<Record<string, unknown>>, timeoutMs?: number): Promise<unknown>;
}

export interface CleanupFailure {
  readonly name: string;
  readonly code: string;
}

export class CleanupStack {
  private readonly operations: { readonly name: string; readonly operation: () => Promise<void> }[] = [];
  private closed = false;

  defer(name: string, operation: () => Promise<void>): void {
    if (this.closed) throw new HarnessError("E2E_CLEANUP_ALREADY_CLOSED");
    this.operations.push({ name, operation });
  }

  async close(): Promise<readonly CleanupFailure[]> {
    if (this.closed) return [];
    this.closed = true;
    const failures: CleanupFailure[] = [];
    for (const item of this.operations.reverse()) {
      try {
        await item.operation();
      } catch (error) {
        failures.push({ name: item.name, code: errorCode(error) });
      }
    }
    return failures;
  }
}

export function registerHostAuthenticationRecovery(
  cleanup: CleanupStack,
  originalHostState: E2eHostStateSnapshot["summary"],
  state: HostAuthenticationRecoveryState,
  operations: HostAuthenticationRecoveryOperations,
): void {
  cleanup.defer("host-authentication-state", async () => {
    const snapshot = state.snapshot;
    try {
      if (snapshot !== undefined) {
        await operations.stopIfRunning();
        await operations.restore(snapshot);
        delete state.snapshot;
      }
    } finally {
      await operations.startOrVerify();
    }
    const restoredStatus = await operations.awaitReady();
    const restoredState = await operations.inspect();
    if (restoredStatus.relay.state !== "ready" || !sameHostState(restoredState, originalHostState)) {
      throw new HarnessError("E2E_HOST_RESTORE_VERIFY_FAILED");
    }
  });
}

export interface InstalledStackOptions {
  readonly repositoryRoot: string;
  readonly serial: string;
  readonly dataDirectory: string;
  readonly artifactRoot: string;
  readonly allowDestructiveSession?: boolean;
  readonly selectedHooks?: readonly E2eHook[];
  readonly knownContent: string;
  readonly prompt: string;
  readonly expectedReply: string;
  readonly terminalCanary: string;
  readonly javaHome?: string;
  readonly isolateHostAuthentication?: boolean;
}

interface DaemonStatus {
  readonly ok: boolean;
  readonly sessions: readonly string[];
  readonly relay: { readonly state: string };
  readonly terminal: { readonly available: boolean };
  readonly protocolMajor: number;
  readonly piVersion: string;
}

interface PairBegin {
  readonly uri: string;
  readonly expiresAtMs: number;
}

interface PairedDevice {
  readonly deviceId: string;
}

interface ApkSet {
  readonly debug: string;
  readonly test: string;
  readonly release: string;
}

interface E2eSessionHandle {
  readonly sessionId: string;
  readonly deleteToken: string;
}

export interface RunLock {
  release(): Promise<void>;
}

export interface HostAuthenticationRecoveryState {
  snapshot?: E2eHostStateSnapshot;
}

export interface HostAuthenticationRecoveryOperations {
  stopIfRunning(): Promise<void>;
  restore(snapshot: E2eHostStateSnapshot): Promise<void>;
  startOrVerify(): Promise<void>;
  awaitReady(): Promise<{ readonly relay: { readonly state: string } }>;
  inspect(): Promise<E2eHostStateSnapshot["summary"]>;
}

export type HookOutcome = "passed" | "failed" | "not-run" | "not-selected";

export interface HookResultEvidence {
  readonly hook: E2eHook;
  readonly outcome: HookOutcome;
  readonly failureCode?: string;
}

interface RunEvidence {
  readonly startedAt: string;
  readonly finishedAt: string;
  readonly success: boolean;
  readonly failureCode?: string;
  readonly sessionSetSha256: string;
  readonly sessionCount: number;
  readonly protocolMajor: number;
  readonly piVersion: string;
  readonly selectedHooks: readonly E2eHook[];
  readonly hookResults: readonly HookResultEvidence[];
  readonly cleanupFailures: readonly CleanupFailure[];
}

export const systemProcessRunner: ProcessRunner = {
  run: runProcess,
};

export function unixAdminClient(socketPath: string): AdminClient {
  return {
    call: async (method, params, timeoutMs) => await adminCall(socketPath, method, params, timeoutMs),
  };
}

export async function runInstalledStack(
  options: InstalledStackOptions,
  processRunner: ProcessRunner = systemProcessRunner,
  admin: AdminClient = unixAdminClient(join(options.dataDirectory, "run", "host-admin.sock")),
): Promise<string> {
  const startedAt = new Date().toISOString();
  const cleanup = new CleanupStack();
  let status: DaemonStatus | undefined;
  let apkSet: ApkSet | undefined;
  let failureCode: string | undefined;
  let cleanupFailures: readonly CleanupFailure[];
  let devicesBefore: readonly PairedDevice[] | undefined;
  let originalSessionIds: readonly string[] = [];
  let hookResults = initialHookResults(options.selectedHooks ?? []);
  const hostAuthenticationRecovery: HostAuthenticationRecoveryState = {};
  const runDirectory = options.artifactRoot;
  await mkdir(join(runDirectory, "screenshots"), { recursive: true, mode: 0o700 });
  const runLock = await acquireRunLock(options.serial);
  cleanup.defer("run-lock", async () => await runLock.release());

  try {
    await preflight(options, processRunner);
    requireDestructiveSessionOptIn(options.allowDestructiveSession === true);
    apkSet = await buildApks(options, processRunner);
    await assertReleaseIsolation(processRunner, apkSet, options.repositoryRoot);
    status = parseDaemonStatus(await admin.call("status"));
    assertDaemonReady(status);
    originalSessionIds = [...status.sessions].sort();
    const e2eSession = parseE2eSessionHandle(await admin.call("sessions.e2e.create"));
    cleanup.defer("e2e-session", async () => {
      await admin.call("sessions.e2e.delete", { sessionId: e2eSession.sessionId, deleteToken: e2eSession.deleteToken }, 30_000);
      const restored = parseDaemonStatus(await admin.call("status", undefined, 15_000));
      if (!sameSessionSet(restored.sessions, originalSessionIds)) throw new HarnessError("E2E_SESSION_SET_RESTORE_FAILED");
    });
    if (originalSessionIds.includes(e2eSession.sessionId)) throw new HarnessError("E2E_SESSION_OWNERSHIP_REQUIRED");
    await seedOwnedSession(admin, e2eSession, options.knownContent);
    status = parseDaemonStatus(await admin.call("status"));
    assertDaemonReady(status);
    if (!sameSessionSet(status.sessions, [...originalSessionIds, e2eSession.sessionId])) {
      throw new HarnessError("E2E_SESSION_SET_INVALID");
    }
    if (options.isolateHostAuthentication === true) {
      const originalHostState = await inspectHostState(options.dataDirectory);
      const hostLock = await acquireHostStoreLock(options.dataDirectory);
      cleanup.defer("host-store-lock", async () => await hostLock.release());
      registerHostAuthenticationRecovery(cleanup, originalHostState, hostAuthenticationRecovery, {
        stopIfRunning: async () => await stopHostDaemonIfRunning(admin, processRunner, options),
        restore: async (snapshot) => await restoreHostState(options.dataDirectory, snapshot),
        startOrVerify: async () => await ensureHostDaemonRunning(admin, processRunner, options),
        awaitReady: async () => await awaitDaemonReady(admin),
        inspect: async () => await inspectHostState(options.dataDirectory),
      });
      await stopHostDaemon(admin, processRunner, options);
      hostAuthenticationRecovery.snapshot = await isolateHostState(
        options.dataDirectory,
        join(tmpdir(), "pi-mobile-e2e-private"),
        (snapshot) => { hostAuthenticationRecovery.snapshot = snapshot; },
      );
      await startHostDaemon(processRunner, options);
    }
    status = parseDaemonStatus(await admin.call("status"));
    assertDaemonReady(status);
    if (!sameSessionSet(status.sessions, [...originalSessionIds, e2eSession.sessionId])) {
      throw new HarnessError("E2E_SESSION_SET_INVALID");
    }
    await resetAndInstall(processRunner, options.serial, apkSet, options.repositoryRoot);
    await clearHookResults(processRunner, options.serial, options.repositoryRoot);
    cleanup.defer("installed-packages", async () => {
      await uninstallPackage(processRunner, options.serial, TEST_PACKAGE, options.repositoryRoot);
      await uninstallPackage(processRunner, options.serial, APP_PACKAGE, options.repositoryRoot);
    });
    cleanup.defer("instrumentation-quiescence", async () => {
      await stopInstalledStackInstrumentation(processRunner, options.serial, options.repositoryRoot);
    });
    devicesBefore = parseDevices(await admin.call("devices.list"));

    const invitation = parsePairBegin(await admin.call("pair.begin"));
    const channel = await stageOneUseChannel(
      processRunner,
      cleanup,
      options,
      {
        invitationUri: invitation.uri,
        expectedSessionIds: status.sessions,
        knownSessionId: e2eSession.sessionId,
        knownContent: options.knownContent,
        prompt: options.prompt,
        expectedReply: options.expectedReply,
        terminalCanary: options.terminalCanary,
        pairingTimeoutMillis: Math.max(1_000, invitation.expiresAtMs - Date.now()),
        syncTimeoutMillis: 120_000,
        replyTimeoutMillis: 180_000,
        terminalTimeoutMillis: 60_000,
      },
    );

    const controller = new AbortController();
    const instrument = processRunner.run(
      "adb",
      installedStackInstrumentationArgs(options.serial, channel, e2eSession.sessionId, options.selectedHooks ?? []),
      { cwd: options.repositoryRoot, timeoutMs: 10 * 60_000, signal: controller.signal },
    );
    const pairing = confirmPairing(admin, invitation.expiresAtMs, controller.signal).then(
      () => ({ ok: true as const, triggeredAbort: false as const }),
      async (error: unknown) => {
        const triggeredAbort = !controller.signal.aborted;
        if (triggeredAbort) {
          try {
            await stopInstalledStackInstrumentation(processRunner, options.serial, options.repositoryRoot);
          } finally {
            controller.abort();
          }
        }
        return { ok: false as const, error, triggeredAbort };
      },
    );
    try {
      let instrumentResult: ProcessResult;
      try {
        instrumentResult = await instrument;
      } catch (error) {
        controller.abort();
        await stopInstalledStackInstrumentation(processRunner, options.serial, options.repositoryRoot);
        await pairing;
        throw error;
      }
      hookResults = await collectHookResults(processRunner, options, runDirectory, options.selectedHooks ?? []);
      const instrumentFailure = instrumentationFailureCode(instrumentResult);
      if (instrumentFailure !== undefined) controller.abort();
      const pairingResult = await pairing;
      if (!pairingResult.ok && pairingResult.triggeredAbort) throw pairingResult.error;
      if (instrumentFailure !== undefined) throw new HarnessError(instrumentFailure);
      if (!pairingResult.ok) throw pairingResult.error;
      assertSelectedHooksPassed(hookResults);
    } finally {
      controller.abort();
    }

    await pullScreenshots(processRunner, options.serial, runDirectory, options.repositoryRoot);
    await assertScreenshots(runDirectory);
  } catch (error) {
    failureCode = errorCode(error);
  } finally {
    if (devicesBefore !== undefined) {
      await revokeNewDevices(admin, devicesBefore).catch((error: unknown) => {
        failureCode ??= errorCode(error);
      });
    }
    cleanupFailures = await cleanup.close();
    if (cleanupFailures.length > 0) failureCode ??= "E2E_TEARDOWN_FAILED";
  }

  const safeStatus = status ?? {
    ok: false,
    sessions: [],
    relay: { state: "unknown" },
    terminal: { available: false },
    protocolMajor: 0,
    piVersion: "unknown",
  };
  const finishedAt = new Date().toISOString();
  const evidence: RunEvidence = {
    startedAt,
    finishedAt,
    success: failureCode === undefined,
    ...(failureCode === undefined ? {} : { failureCode }),
    sessionSetSha256: sha256Text([...safeStatus.sessions].sort().join("\n")),
    sessionCount: safeStatus.sessions.length,
    protocolMajor: safeStatus.protocolMajor,
    piVersion: safeStatus.piVersion,
    selectedHooks: options.selectedHooks ?? [],
    hookResults,
    cleanupFailures,
  };
  await writeEvidence(runDirectory, evidence, apkSet);
  if (failureCode !== undefined) throw new HarnessError(failureCode);
  return runDirectory;
}

export async function acquireRunLock(serial: string): Promise<RunLock> {
  const lockPath = join(tmpdir(), `pi-mobile-e2e-run-${sha256Text(serial).slice(0, 16)}.lock`);
  return await acquireDirectoryLock(lockPath);
}

async function acquireHostStoreLock(dataDirectory: string): Promise<RunLock> {
  return await acquireDirectoryLock(`${resolve(dataDirectory)}.e2e-host-store.lock`);
}

async function acquireDirectoryLock(lockPath: string): Promise<RunLock> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    let created = false;
    try {
      await mkdir(lockPath, { mode: 0o700 });
      created = true;
      await writeFile(join(lockPath, "owner.json"), `${JSON.stringify({ pid: process.pid })}\n`, { mode: 0o600, flag: "wx" });
      let released = false;
      return {
        release: async () => {
          if (released) return;
          released = true;
          await rm(lockPath, { recursive: true, force: true });
        },
      };
    } catch (error) {
      if (created) {
        await rm(lockPath, { recursive: true, force: true });
        throw new HarnessError("E2E_RUN_LOCK_FAILED", { cause: error });
      }
      if (!hasErrorCode(error, "EEXIST")) throw new HarnessError("E2E_RUN_LOCK_FAILED", { cause: error });
      const owner = await readFile(join(lockPath, "owner.json"), "utf8").catch(() => "");
      const pid = parseLockPid(owner);
      if (pid === undefined || processExists(pid)) throw new HarnessError("E2E_RUN_ALREADY_ACTIVE");
      const stalePath = `${lockPath}.stale-${randomBytes(8).toString("hex")}`;
      try {
        await rename(lockPath, stalePath);
        await rm(stalePath, { recursive: true, force: true });
      } catch (renameError) {
        if (!hasErrorCode(renameError, "ENOENT")) throw new HarnessError("E2E_RUN_LOCK_FAILED", { cause: renameError });
      }
    }
  }
  throw new HarnessError("E2E_RUN_ALREADY_ACTIVE");
}

export async function stageOneUseChannel(
  runner: ProcessRunner,
  cleanup: CleanupStack,
  options: Pick<InstalledStackOptions, "repositoryRoot" | "serial">,
  payload: Readonly<Record<string, unknown>>,
): Promise<string> {
  const token = randomBytes(32).toString("base64url");
  const localDirectory = await mkdtemp(join(tmpdir(), "pi-mobile-e2e-"));
  await chmod(localDirectory, 0o700);
  const localPath = join(localDirectory, "config.json");
  const remoteDirectory = `/data/local/tmp/pi-mobile-e2e-${token}`;
  const remotePath = `${remoteDirectory}/config`;
  cleanup.defer("one-use-channel", async () => {
    try {
      const result = await runner.run("adb", ["-s", options.serial, "shell", "rm", "-rf", remoteDirectory], {
        cwd: options.repositoryRoot,
        timeoutMs: 15_000,
      });
      if (result.code !== 0) throw new HarnessError("E2E_CHANNEL_CLEANUP_FAILED");
    } finally {
      await rm(localDirectory, { recursive: true, force: true });
    }
  });
  const bytes = channelPayloadBytes(payload);
  await writeAtomicChannel(localDirectory, localPath, bytes);
  const digest = createHash("sha256").update(bytes).digest("hex");
  await requireSuccess(runner, "adb", ["-s", options.serial, "shell", "mkdir", "-m", "700", remoteDirectory], options.repositoryRoot, "E2E_CHANNEL_CREATE_FAILED");
  try {
    await requireSuccess(runner, "adb", ["-s", options.serial, "push", localPath, remotePath], options.repositoryRoot, "E2E_CHANNEL_PUSH_FAILED");
    await requireSuccess(runner, "adb", ["-s", options.serial, "shell", "chmod", "600", remotePath], options.repositoryRoot, "E2E_CHANNEL_MODE_FAILED");
    await verifyRemoteChannel(runner, options, remotePath, bytes.byteLength, digest);
  } finally {
    await rm(localDirectory, { recursive: true, force: true });
  }
  return remotePath;
}

function channelPayloadBytes(payload: Readonly<Record<string, unknown>>): Uint8Array {
  let serialized: string;
  try {
    serialized = JSON.stringify(payload);
  } catch (error) {
    throw new HarnessError("E2E_CHANNEL_JSON_INVALID", { cause: error });
  }
  const bytes = Buffer.from(serialized, "utf8");
  if (bytes.byteLength === 0 || bytes.byteLength > MAX_CHANNEL_BYTES) {
    throw new HarnessError("E2E_CHANNEL_PAYLOAD_TOO_LARGE");
  }
  return bytes;
}

async function writeAtomicChannel(directory: string, destination: string, bytes: Uint8Array): Promise<void> {
  const temporary = join(directory, "config.tmp");
  const handle = await open(temporary, "wx", 0o600);
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
  await rename(temporary, destination);
  const metadata = await stat(destination);
  if (!metadata.isFile() || (metadata.mode & 0o777) !== 0o600 || metadata.size !== bytes.byteLength) {
    throw new HarnessError("E2E_CHANNEL_LOCAL_VERIFY_FAILED");
  }
}

async function verifyRemoteChannel(
  runner: ProcessRunner,
  options: Pick<InstalledStackOptions, "repositoryRoot" | "serial">,
  remotePath: string,
  expectedBytes: number,
  expectedDigest: string,
): Promise<void> {
  const metadata = await requireSuccess(
    runner,
    "adb",
    ["-s", options.serial, "shell", "stat", "-c", "%a:%s", remotePath],
    options.repositoryRoot,
    "E2E_CHANNEL_REMOTE_STAT_FAILED",
  );
  const metadataMatch = /^600:([0-9]+)$/u.exec(metadata.stdout.trim());
  if (metadataMatch === null || Number(metadataMatch[1]) !== expectedBytes) {
    throw new HarnessError("E2E_CHANNEL_REMOTE_SIZE_MISMATCH");
  }
  const digest = await requireSuccess(
    runner,
    "adb",
    ["-s", options.serial, "shell", "sha256sum", remotePath],
    options.repositoryRoot,
    "E2E_CHANNEL_REMOTE_DIGEST_FAILED",
  );
  const digestParts = digest.stdout.trim().split(/\s+/u);
  if (digestParts.length !== 2 || digestParts[1] !== remotePath || !/^[a-f0-9]{64}$/u.test(digestParts[0] ?? "")) {
    throw new HarnessError("E2E_CHANNEL_REMOTE_DIGEST_INVALID");
  }
  if (digestParts[0] !== expectedDigest) throw new HarnessError("E2E_CHANNEL_REMOTE_DIGEST_MISMATCH");
}

export function installedStackInstrumentationArgs(
  serial: string,
  channelPath: string,
  sessionId: string,
  selectedHooks: readonly E2eHook[],
): readonly string[] {
  const match = E2E_CHANNEL_PATH.exec(channelPath);
  if (match === null) throw new HarnessError("E2E_CHANNEL_PATH_INVALID");
  const runToken = match[1];
  if (runToken === undefined) throw new HarnessError("E2E_CHANNEL_PATH_INVALID");
  if (!isSessionId(sessionId) || !areValidHooks(selectedHooks)) throw new HarnessError("E2E_HOOK_SELECTION_INVALID");
  return [
    "-s", serial, "shell", "am", "instrument", "-w", "-r",
    "-e", "class", INSTALLED_STACK_TESTS,
    "-e", "e2eChannelPath", channelPath,
    "-e", "e2eRunToken", runToken,
    "-e", "e2eSessionId", sessionId,
    "-e", "e2eHooks", selectedHooks.join(","),
    TEST_RUNNER,
  ];
}

export function releaseContainsE2eClasses(packages: string): boolean {
  return packages.split(/\r?\n/u).some((line) =>
    /(?:^|[.$/])e2e(?:[.$/]|$)|InstalledStackE2e|AppLaunchTestBridge/u.test(line),
  );
}

export function instrumentationPassed(result: ProcessResult): boolean {
  return result.code === 0
    && !result.stdout.includes("FAILURES!!!")
    && !result.stdout.includes("INSTRUMENTATION_FAILED")
    && /OK \([1-9][0-9]* tests?\)/u.test(result.stdout);
}

export function instrumentationFailureCode(result: ProcessResult): string | undefined {
  if (instrumentationPassed(result)) return undefined;
  const output = `${result.stdout}\n${result.stderr}`;
  const matches = [...output.matchAll(/\bE2E_[A-Z0-9_]{1,96}\b/gu)];
  if (matches.length > 0) return matches.at(-1)?.[0] ?? "E2E_INSTRUMENTATION_FAILED";
  if (output.includes("Process crashed.")) return "E2E_INSTRUMENTATION_PROCESS_CRASHED";
  return "E2E_INSTRUMENTATION_FAILED";
}

export async function preflight(options: InstalledStackOptions, runner: ProcessRunner): Promise<void> {
  if (!/^[A-Za-z0-9._:-]{1,128}$/u.test(options.serial)) throw new HarnessError("E2E_SERIAL_INVALID");
  if (options.isolateHostAuthentication !== true) throw new HarnessError("E2E_HOST_AUTH_ISOLATION_REQUIRED");
  for (const value of [options.knownContent, options.prompt, options.expectedReply, options.terminalCanary]) {
    if (value.length === 0 || value.length > 512 || value.includes("\u0000") || value.includes("\r")) {
      throw new HarnessError("E2E_CANARY_INVALID");
    }
  }
  const socketPath = join(options.dataDirectory, "run", "host-admin.sock");
  const socket = await stat(socketPath).catch(() => undefined);
  if (socket === undefined || !socket.isSocket() || (socket.mode & 0o777) !== 0o600) {
    throw new HarnessError("E2E_ADMIN_SOCKET_UNSAFE");
  }
  if (!options.serial.startsWith("emulator-")) throw new HarnessError("E2E_PHYSICAL_DEVICE_REJECTED");
  const device = await runner.run("adb", ["-s", options.serial, "get-state"], {
    cwd: options.repositoryRoot,
    timeoutMs: 15_000,
  });
  if (device.code !== 0 || device.stdout.trim() !== "device") throw new HarnessError("E2E_EMULATOR_UNAVAILABLE");
  const avd = await runner.run("adb", ["-s", options.serial, "emu", "avd", "name"], {
    cwd: options.repositoryRoot,
    timeoutMs: 15_000,
  });
  const avdName = parseConsoleAvdName(avd);
  const qemu = await runner.run("adb", ["-s", options.serial, "shell", "getprop", "ro.kernel.qemu"], {
    cwd: options.repositoryRoot,
    timeoutMs: 15_000,
  });
  const bootAvd = await runner.run("adb", ["-s", options.serial, "shell", "getprop", "ro.boot.qemu.avd_name"], {
    cwd: options.repositoryRoot,
    timeoutMs: 15_000,
  });
  const bootAvdName = bootAvd.stdout.trim();
  if (qemu.code !== 0 || qemu.stdout.trim() !== "1" || bootAvd.code !== 0
    || (bootAvdName.length > 0 && bootAvdName !== avdName)) {
    throw new HarnessError("E2E_EMULATOR_IDENTITY_INVALID");
  }
}

function parseConsoleAvdName(result: ProcessResult): string {
  const match = /^([^\r\n]+)\r?\nOK\r?\n$/u.exec(result.stdout);
  if (result.code !== 0 || match === null || !ALLOWED_E2E_AVD_NAMES.has(match[1] ?? "")) {
    throw new HarnessError("E2E_AVD_NOT_ALLOWLISTED");
  }
  return match[1] ?? "";
}

function assertDaemonReady(status: DaemonStatus): void {
  if (!status.ok) throw new HarnessError("E2E_DAEMON_UNHEALTHY");
  if (status.relay.state !== "ready") throw new HarnessError("E2E_RELAY_NOT_READY");
  if (!status.terminal.available) throw new HarnessError("E2E_TERMINAL_NOT_READY");
}

export function requireDestructiveSessionOptIn(allowDestructiveSession: boolean): void {
  if (!allowDestructiveSession) throw new HarnessError("E2E_DESTRUCTIVE_SESSION_OPT_IN_REQUIRED");
}

async function seedOwnedSession(admin: AdminClient, session: E2eSessionHandle, content: string): Promise<void> {
  await admin.call("sessions.run", {
    sessionId: session.sessionId,
    operation: "prompt",
    payload: { message: content },
  }, 120_000);
  await admin.call("sessions.run", {
    sessionId: session.sessionId,
    operation: "get_state",
    payload: {},
  }, 30_000);
  const result = asRecord(await admin.call("sessions.e2e.await_canonical", { ...session, content }, 35_000));
  if (result["visible"] !== true) throw new HarnessError("E2E_SESSION_CANONICAL_TIMEOUT");
}

function parseE2eSessionHandle(value: unknown): E2eSessionHandle {
  const record = asRecord(value);
  const sessionId = record["sessionId"];
  const deleteToken = record["deleteToken"];
  if (typeof sessionId !== "string" || !isSessionId(sessionId)
    || typeof deleteToken !== "string" || !/^[A-Za-z0-9_-]{43}$/u.test(deleteToken)) {
    throw new HarnessError("E2E_SESSION_CREATE_INVALID");
  }
  return { sessionId, deleteToken };
}

function sameSessionSet(left: readonly string[], right: readonly string[]): boolean {
  return left.length === right.length && [...left].sort().every((sessionId, index) => sessionId === [...right].sort()[index]);
}

async function buildApks(options: InstalledStackOptions, runner: ProcessRunner): Promise<ApkSet> {
  const env = { ...process.env, JAVA_HOME: options.javaHome ?? defaultJavaHome() };
  await requireSuccess(
    runner,
    join(options.repositoryRoot, "gradlew"),
    [":android:app:assembleDebug", ":android:app:assembleDebugAndroidTest", ":android:app:assembleRelease"],
    options.repositoryRoot,
    "E2E_ANDROID_BUILD_FAILED",
    env,
    15 * 60_000,
  );
  const apkSet = {
    debug: await apkFromMetadata(join(options.repositoryRoot, "android/app/build/outputs/apk/debug")),
    test: await apkFromMetadata(join(options.repositoryRoot, "android/app/build/outputs/apk/androidTest/debug")),
    release: await apkFromMetadata(join(options.repositoryRoot, "android/app/build/outputs/apk/release")),
  };
  await Promise.all(Object.values(apkSet).map(async (path) => await access(path).catch(() => {
    throw new HarnessError("E2E_APK_MISSING");
  })));
  return apkSet;
}

async function apkFromMetadata(directory: string): Promise<string> {
  const metadata = asRecord(JSON.parse(await readFile(join(directory, "output-metadata.json"), "utf8")));
  const elements = metadata["elements"];
  if (!Array.isArray(elements) || elements.length !== 1) throw new HarnessError("E2E_APK_METADATA_INVALID");
  const element = asRecord(elements[0]);
  const outputFile = element["outputFile"];
  if (typeof outputFile !== "string" || basename(outputFile) !== outputFile || !outputFile.endsWith(".apk")) {
    throw new HarnessError("E2E_APK_METADATA_INVALID");
  }
  return join(directory, outputFile);
}

async function assertReleaseIsolation(runner: ProcessRunner, apks: ApkSet, cwd: string): Promise<void> {
  const debugHooks = [
    "io.github.verybigsad.pimobile.e2e.InstalledStackE2eBridge",
    "io.github.verybigsad.pimobile.testing.AppLaunchTestBridge",
  ];
  const testClass = "io.github.verybigsad.pimobile.e2e.InstalledStackE2ETest";
  const releaseHooks = await Promise.all(debugHooks.map(async (className) =>
    await runner.run("apkanalyzer", ["dex", "code", "--class", className, apks.release], { cwd, timeoutMs: 60_000 })));
  const debugHookChecks = await Promise.all(debugHooks.map(async (className) =>
    await runner.run("apkanalyzer", ["dex", "code", "--class", className, apks.debug], { cwd, timeoutMs: 60_000 })));
  const [releaseTest, installedTest, releasePackages] = await Promise.all([
    runner.run("apkanalyzer", ["dex", "code", "--class", testClass, apks.release], { cwd, timeoutMs: 60_000 }),
    runner.run("apkanalyzer", ["dex", "code", "--class", testClass, apks.test], { cwd, timeoutMs: 60_000 }),
    runner.run("apkanalyzer", ["dex", "packages", apks.release], { cwd, timeoutMs: 60_000 }),
  ]);
  const absentChecks = [...releaseHooks, releaseTest];
  if (absentChecks.some((result) => result.code === 0) || (releasePackages.code === 0 && releaseContainsE2eClasses(releasePackages.stdout))) {
    throw new HarnessError("E2E_RELEASE_CONTAINS_TEST_BRIDGE");
  }
  if (absentChecks.some((result) => !result.stderr.includes("not found")) || releasePackages.code !== 0) {
    throw new HarnessError("E2E_APK_ANALYSIS_FAILED");
  }
  if (debugHookChecks.some((result) => result.code !== 0)) throw new HarnessError("E2E_DEBUG_BRIDGE_MISSING");
  if (installedTest.code !== 0) throw new HarnessError("E2E_INSTRUMENTATION_CLASS_MISSING");
}

async function stopHostDaemon(
  admin: AdminClient,
  _runner: ProcessRunner,
  options: InstalledStackOptions,
): Promise<void> {
  await admin.call("stop", undefined, 10_000);
  const deadline = Date.now() + 30_000;
  const socketPath = join(options.dataDirectory, "run", "host-admin.sock");
  while (Date.now() < deadline) {
    const socket = await stat(socketPath).catch(() => undefined);
    if (socket === undefined) return;
    await new Promise<void>((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  throw new HarnessError("E2E_HOST_STOP_TIMEOUT");
}

async function stopHostDaemonIfRunning(
  admin: AdminClient,
  runner: ProcessRunner,
  options: InstalledStackOptions,
): Promise<void> {
  const socket = await stat(join(options.dataDirectory, "run", "host-admin.sock")).catch(() => undefined);
  if (socket === undefined) return;
  await stopHostDaemon(admin, runner, options);
}

async function ensureHostDaemonRunning(
  admin: AdminClient,
  runner: ProcessRunner,
  options: InstalledStackOptions,
): Promise<void> {
  const socket = await stat(join(options.dataDirectory, "run", "host-admin.sock")).catch(() => undefined);
  if (socket === undefined) {
    await startHostDaemon(runner, options);
  } else {
    await awaitDaemonReady(admin);
  }
}

async function startHostDaemon(runner: ProcessRunner, options: InstalledStackOptions): Promise<void> {
  const child = spawn(
    process.execPath,
    [join(options.repositoryRoot, "mac", "host", "dist", "src", "cli.js"), "serve", "--data-dir", options.dataDirectory],
    {
      cwd: options.repositoryRoot,
      env: process.env,
      detached: true,
      stdio: "ignore",
    },
  );
  child.unref();
  const status = await awaitDaemonReady(unixAdminClient(join(options.dataDirectory, "run", "host-admin.sock")));
  assertDaemonReady(status);
  void runner;
}

async function awaitDaemonReady(admin: AdminClient): Promise<DaemonStatus> {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const value = await admin.call("status", undefined, 5_000).catch(() => undefined);
    if (value !== undefined) {
      const status = parseDaemonStatus(value);
      if (status.ok && status.relay.state === "ready") return status;
    }
    await new Promise<void>((resolveDelay) => setTimeout(resolveDelay, 250));
  }
  throw new HarnessError("E2E_HOST_START_TIMEOUT");
}

async function resetAndInstall(runner: ProcessRunner, serial: string, apks: ApkSet, cwd: string): Promise<void> {
  await uninstallPackage(runner, serial, TEST_PACKAGE, cwd);
  await uninstallPackage(runner, serial, APP_PACKAGE, cwd);
  await requireSuccess(runner, "adb", ["-s", serial, "install", "--no-streaming", "-r", "-t", apks.debug], cwd, "E2E_DEBUG_INSTALL_FAILED", undefined, 120_000);
  await requireSuccess(runner, "adb", ["-s", serial, "install", "--no-streaming", "-r", "-t", apks.test], cwd, "E2E_TEST_INSTALL_FAILED", undefined, 120_000);
}

async function stopInstalledStackInstrumentation(runner: ProcessRunner, serial: string, cwd: string): Promise<void> {
  await requireSuccess(
    runner,
    "adb",
    ["-s", serial, "shell", "am", "force-stop", APP_PACKAGE],
    cwd,
    "E2E_INSTRUMENTATION_STOP_FAILED",
    undefined,
    30_000,
  );
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    const processState = await runner.run("adb", ["-s", serial, "shell", "pidof", APP_PACKAGE], {
      cwd,
      timeoutMs: 10_000,
    });
    if ((processState.code === 0 || processState.code === 1) && processState.stdout.trim().length === 0) return;
    if (processState.code !== 0 || !/^[0-9]+(?:\s+[0-9]+)*\s*$/u.test(processState.stdout)) {
      throw new HarnessError("E2E_INSTRUMENTATION_STATE_INVALID");
    }
    await new Promise<void>((resolveDelay) => setTimeout(resolveDelay, 100));
  }
  throw new HarnessError("E2E_INSTRUMENTATION_STILL_RUNNING");
}

export async function uninstallPackage(runner: ProcessRunner, serial: string, packageName: string, cwd: string): Promise<void> {
  if (!await packageInstalled(runner, serial, packageName, cwd)) return;
  const result = await runner.run("adb", ["-s", serial, "uninstall", packageName], { cwd, timeoutMs: 60_000 });
  if (!await packageInstalled(runner, serial, packageName, cwd)) return;
  if (result.code !== 0) throw new HarnessError("E2E_UNINSTALL_FAILED");
  throw new HarnessError("E2E_UNINSTALL_INCOMPLETE");
}

async function packageInstalled(runner: ProcessRunner, serial: string, packageName: string, cwd: string): Promise<boolean> {
  const result = await runner.run("adb", ["-s", serial, "shell", "pm", "path", packageName], { cwd, timeoutMs: 30_000 });
  if (result.code !== 0) {
    if (result.code === 1 && result.stdout.trim().length === 0 && result.stderr.trim().length === 0) return false;
    throw new HarnessError("E2E_PACKAGE_QUERY_FAILED");
  }
  const lines = result.stdout.trim().split(/\r?\n/u).filter((line) => line.length > 0);
  if (lines.length === 0) return false;
  if (lines.every((line) => line.startsWith("package:"))) return true;
  throw new HarnessError("E2E_PACKAGE_QUERY_INVALID");
}

async function confirmPairing(admin: AdminClient, expiresAtMs: number, signal: AbortSignal): Promise<void> {
  while (Date.now() < expiresAtMs) {
    if (signal.aborted) throw new HarnessError("E2E_PAIRING_ABORTED");
    const status = asRecord(await admin.call("pair.status", undefined, 10_000));
    const state = status["state"];
    if (state === "issued") return;
    if (state === "failed") throw new HarnessError("E2E_PAIRING_FAILED");
    if (state === "awaiting_local_confirmation") {
      const confirmed = asRecord(await admin.call("pair.confirm", { approved: true }, 10_000));
      if (confirmed["state"] === "issued") return;
    }
    await abortableDelay(250, signal);
  }
  throw new HarnessError("E2E_PAIRING_EXPIRED");
}

async function pullScreenshots(runner: ProcessRunner, serial: string, runDirectory: string, cwd: string): Promise<void> {
  const remote = `/sdcard/Android/data/${APP_PACKAGE}/files/e2e`;
  const destination = join(runDirectory, "screenshots");
  await requireSuccess(runner, "adb", ["-s", serial, "pull", `${remote}/.`, destination], cwd, "E2E_SCREENSHOT_PULL_FAILED");
}

async function assertScreenshots(runDirectory: string): Promise<void> {
  for (const name of ["pairing-title.png", "agents-title.png"]) {
    const bytes = await readFile(join(runDirectory, "screenshots", name)).catch(() => undefined);
    if (bytes === undefined || bytes.byteLength < 32 || bytes.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a") {
      throw new HarnessError("E2E_SCREENSHOT_INVALID");
    }
  }
}

async function revokeNewDevices(admin: AdminClient, before: readonly PairedDevice[]): Promise<void> {
  const beforeIds = new Set(before.map((device) => device.deviceId));
  const after = parseDevices(await admin.call("devices.list", undefined, 10_000));
  for (const device of after) {
    if (!beforeIds.has(device.deviceId)) await admin.call("devices.revoke", { deviceId: device.deviceId }, 15_000);
  }
}

async function clearHookResults(runner: ProcessRunner, serial: string, cwd: string): Promise<void> {
  await requireSuccess(
    runner,
    "adb",
    ["-s", serial, "shell", "rm", "-f", `/sdcard/Android/data/${APP_PACKAGE}/files/e2e/hook-results.json`],
    cwd,
    "E2E_HOOK_EVIDENCE_CLEAR_FAILED",
    undefined,
    30_000,
  );
}

function initialHookResults(selectedHooks: readonly E2eHook[]): readonly HookResultEvidence[] {
  return E2E_HOOKS.map((hook) => ({
    hook,
    outcome: selectedHooks.includes(hook) ? "not-run" : "not-selected",
  }));
}

async function collectHookResults(
  runner: ProcessRunner,
  options: Pick<InstalledStackOptions, "repositoryRoot" | "serial">,
  directory: string,
  selectedHooks: readonly E2eHook[],
): Promise<readonly HookResultEvidence[]> {
  const destination = join(directory, "hook-results.json");
  const remote = `/sdcard/Android/data/${APP_PACKAGE}/files/e2e/hook-results.json`;
  const result = await runner.run("adb", ["-s", options.serial, "pull", remote, destination], {
    cwd: options.repositoryRoot,
    timeoutMs: 30_000,
  });
  if (result.code !== 0) return initialHookResults(selectedHooks);
  try {
    const bytes = await readFile(destination);
    if (bytes.byteLength === 0 || bytes.byteLength > MAX_HOOK_EVIDENCE_BYTES) {
      throw new HarnessError("E2E_HOOK_EVIDENCE_INVALID");
    }
    return parseHookResults(JSON.parse(bytes.toString("utf8")), selectedHooks);
  } catch (error) {
    if (error instanceof HarnessError) throw error;
    throw new HarnessError("E2E_HOOK_EVIDENCE_INVALID", { cause: error });
  } finally {
    await rm(destination, { force: true });
  }
}

export function parseHookResults(value: unknown, selectedHooks: readonly E2eHook[]): readonly HookResultEvidence[] {
  const record = asRecord(value);
  if (!hasOnlyKeys(record, ["version", "hooks"])
    || record["version"] !== 1 || !Array.isArray(record["hooks"]) || record["hooks"].length > E2E_HOOKS.length) {
    throw new HarnessError("E2E_HOOK_EVIDENCE_INVALID");
  }
  const reported = new Map<E2eHook, HookResultEvidence>();
  for (const item of record["hooks"]) {
    const entry = asRecord(item);
    const hook = entry["hook"];
    const outcome = entry["outcome"];
    const failureCode = entry["failureCode"];
    if (!hasOnlyKeys(entry, ["hook", "outcome", "failureCode"])
      || typeof hook !== "string" || !E2E_HOOKS.includes(hook as E2eHook) || reported.has(hook as E2eHook)
      || (outcome !== "passed" && outcome !== "failed")
      || (outcome === "passed" && failureCode !== undefined)
      || (outcome === "failed" && (typeof failureCode !== "string" || !isE2eCode(failureCode)))) {
      throw new HarnessError("E2E_HOOK_EVIDENCE_INVALID");
    }
    if (!selectedHooks.includes(hook as E2eHook)) throw new HarnessError("E2E_HOOK_EVIDENCE_INVALID");
    reported.set(hook as E2eHook, {
      hook: hook as E2eHook,
      outcome,
      ...(typeof failureCode === "string" ? { failureCode } : {}),
    });
  }
  return E2E_HOOKS.map((hook) => reported.get(hook) ?? {
    hook,
    outcome: selectedHooks.includes(hook) ? "not-run" : "not-selected",
  });
}

function assertSelectedHooksPassed(results: readonly HookResultEvidence[]): void {
  const failed = results.find((result) => result.outcome === "failed");
  if (failed !== undefined) throw new HarnessError(failed.failureCode ?? "E2E_HOOK_FAILED");
  if (results.some((result) => result.outcome === "not-run")) throw new HarnessError("E2E_HOOK_EVIDENCE_INCOMPLETE");
}

async function writeEvidence(directory: string, evidence: RunEvidence, apks: ApkSet | undefined): Promise<void> {
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const manifestPath = join(directory, "run.json");
  const xmlPath = join(directory, "results.xml");
  await writeFile(manifestPath, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
  await writeFile(xmlPath, junitXml(evidence), { mode: 0o600 });
  const hashEntries: string[] = [];
  for (const path of [manifestPath, xmlPath, join(directory, "screenshots", "pairing-title.png"), join(directory, "screenshots", "agents-title.png")]) {
    const digest = await sha256File(path).catch(() => undefined);
    if (digest !== undefined) hashEntries.push(`${digest}  ${relativeEvidenceName(directory, path)}`);
  }
  if (apks !== undefined) {
    for (const [label, path] of [["debug", apks.debug], ["test", apks.test], ["release", apks.release]] as const) {
      hashEntries.push(`${await sha256File(path)}  apk:${label}:${basename(path)}`);
    }
  }
  await writeFile(join(directory, "hashes.sha256"), `${hashEntries.join("\n")}\n`, { mode: 0o600 });
}

function junitXml(evidence: RunEvidence): string {
  const failedHook = evidence.hookResults.find((result) => result.outcome === "failed");
  const rootFailure = evidence.success || failedHook?.failureCode === evidence.failureCode
    ? ""
    : `<failure type="installed-stack" message="${xmlEscape(evidence.failureCode ?? "E2E_FAILED")}"/>`;
  const cleanup = evidence.cleanupFailures.length === 0
    ? ""
    : `<failure type="teardown" message="E2E_TEARDOWN_FAILED"/>`;
  const hookCase = (name: string, hook: E2eHook): string => {
    const result = evidence.hookResults.find((item) => item.hook === hook) ?? { hook, outcome: "not-run" as const };
    if (result.outcome === "passed") return `<testcase name="${name}"/>`;
    if (result.outcome === "failed") {
      return `<testcase name="${name}"><failure type="hook" message="${xmlEscape(result.failureCode ?? "E2E_HOOK_FAILED")}"/></testcase>`;
    }
    return `<testcase name="${name}"><skipped message="${result.outcome === "not-selected" ? "E2E_HOOK_NOT_SELECTED" : "E2E_HOOK_NOT_RUN"}"/></testcase>`;
  };
  const failures = Number(rootFailure.length > 0) + Number(cleanup.length > 0) + evidence.hookResults.filter((result) => result.outcome === "failed").length;
  const skipped = evidence.hookResults.filter((result) => result.outcome === "not-selected" || result.outcome === "not-run").length;
  return `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="installed-stack" tests="8" failures="${String(failures)}" skipped="${String(skipped)}" timestamp="${xmlEscape(evidence.startedAt)}">\n  <testcase name="fresh-install-pair-unlock-sync">${rootFailure}${cleanup}</testcase>\n  <testcase name="known-content-and-final-reply"/>\n  <testcase name="terminal-input-canary"/>\n  <testcase name="agents-state"/>\n  <testcase name="release-isolation"/>\n  ${hookCase("push-hook", "push")}\n  ${hookCase("voice-hook", "voice")}\n  ${hookCase("external-distributor-gate", "external-push")}\n</testsuite>\n`;
}

async function adminCall(
  socketPath: string,
  method: string,
  params: Readonly<Record<string, unknown>> | undefined,
  timeoutMs = 15_000,
): Promise<unknown> {
  return await new Promise<unknown>((resolveCall, rejectCall) => {
    const socket = createConnection(socketPath);
    const timer = setTimeout(() => {
      socket.destroy();
      rejectCall(new HarnessError("E2E_ADMIN_TIMEOUT"));
    }, timeoutMs);
    let response = Buffer.alloc(0);
    const finish = (operation: () => void): void => {
      clearTimeout(timer);
      socket.destroy();
      operation();
    };
    socket.once("connect", () => {
      socket.write(`${JSON.stringify({ method, ...(params === undefined ? {} : { params }) })}\n`);
    });
    socket.on("data", (chunk: Buffer) => {
      response = Buffer.concat([response, chunk]);
      if (response.byteLength > MAX_ADMIN_BYTES) {
        finish(() => rejectCall(new HarnessError("E2E_ADMIN_RESPONSE_TOO_LARGE")));
        return;
      }
      const newline = response.indexOf(0x0a);
      if (newline === -1) return;
      finish(() => {
        try {
          const value = asRecord(JSON.parse(response.subarray(0, newline).toString("utf8")));
          if (value["ok"] === true) resolveCall(value["result"]);
          else {
            const error = asRecord(value["error"]);
            const code = error["code"];
            rejectCall(new HarnessError(typeof code === "string" && /^[A-Z][A-Z0-9_]{1,63}$/u.test(code) ? code : "E2E_ADMIN_REJECTED"));
          }
        } catch (error) {
          rejectCall(new HarnessError("E2E_ADMIN_RESPONSE_INVALID", { cause: error }));
        }
      });
    });
    socket.once("error", (error) => finish(() => rejectCall(new HarnessError("E2E_DAEMON_UNAVAILABLE", { cause: error }))));
  });
}

async function runProcess(command: string, args: readonly string[], options: ProcessOptions = {}): Promise<ProcessResult> {
  return await new Promise<ProcessResult>((resolveProcess, rejectProcess) => {
    const child = spawn(command, [...args], {
      cwd: options.cwd,
      env: options.env,
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout: Uint8Array = new Uint8Array();
    let stderr: Uint8Array = new Uint8Array();
    let overflow = false;
    let timedOut = false;
    const append = (current: Uint8Array, chunk: Uint8Array): Uint8Array => {
      const next = Buffer.concat([current, chunk]);
      if (next.byteLength > MAX_PROCESS_OUTPUT_BYTES) {
        overflow = true;
        child.kill("SIGKILL");
        return next.subarray(0, MAX_PROCESS_OUTPUT_BYTES);
      }
      return next;
    };
    child.stdout.on("data", (chunk: Buffer) => { stdout = append(stdout, chunk); });
    child.stderr.on("data", (chunk: Buffer) => { stderr = append(stderr, chunk); });
    const abort = (): void => {
      child.kill("SIGTERM");
    };
    options.signal?.addEventListener("abort", abort, { once: true });
    if (options.signal?.aborted === true) abort();
    const timer = options.timeoutMs === undefined ? undefined : setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, options.timeoutMs);
    child.once("error", (error) => {
      if (timer !== undefined) clearTimeout(timer);
      options.signal?.removeEventListener("abort", abort);
      rejectProcess(new HarnessError("E2E_PROCESS_START_FAILED", { cause: error }));
    });
    child.once("close", (code) => {
      if (timer !== undefined) clearTimeout(timer);
      options.signal?.removeEventListener("abort", abort);
      if (overflow) {
        rejectProcess(new HarnessError("E2E_PROCESS_OUTPUT_TOO_LARGE"));
        return;
      }
      if (timedOut) {
        rejectProcess(new HarnessError("E2E_PROCESS_TIMEOUT"));
        return;
      }
      resolveProcess({
        code: code ?? -1,
        stdout: Buffer.from(stdout).toString("utf8"),
        stderr: Buffer.from(stderr).toString("utf8"),
      });
    });
    if (options.input === undefined) child.stdin.end();
    else child.stdin.end(options.input);
  });
}

async function requireSuccess(
  runner: ProcessRunner,
  command: string,
  args: readonly string[],
  cwd: string,
  code: string,
  env?: NodeJS.ProcessEnv,
  timeoutMs = 120_000,
): Promise<ProcessResult> {
  const result = await runner.run(command, args, {
    cwd,
    timeoutMs,
    ...(env === undefined ? {} : { env }),
  });
  if (result.code !== 0) throw new HarnessError(code);
  return result;
}

function parseDaemonStatus(value: unknown): DaemonStatus {
  const record = asRecord(value);
  const relay = asRecord(record["relay"]);
  const terminal = asRecord(record["terminal"]);
  const sessions = record["sessions"];
  if (typeof record["ok"] !== "boolean" || !Array.isArray(sessions) || sessions.some((item) => typeof item !== "string")) {
    throw new HarnessError("E2E_DAEMON_STATUS_INVALID");
  }
  if (typeof relay["state"] !== "string" || typeof terminal["available"] !== "boolean"
    || typeof record["protocolMajor"] !== "number" || typeof record["piVersion"] !== "string") {
    throw new HarnessError("E2E_DAEMON_STATUS_INVALID");
  }
  return {
    ok: record["ok"],
    sessions: sessions as string[],
    relay: { state: relay["state"] },
    terminal: { available: terminal["available"] },
    protocolMajor: record["protocolMajor"],
    piVersion: record["piVersion"],
  };
}

function parsePairBegin(value: unknown): PairBegin {
  const record = asRecord(value);
  if (typeof record["uri"] !== "string" || !record["uri"].startsWith("pimobile://pair?v=1&d=")
    || typeof record["expiresAtMs"] !== "number" || !Number.isSafeInteger(record["expiresAtMs"])) {
    throw new HarnessError("E2E_PAIR_INVITATION_INVALID");
  }
  return { uri: record["uri"], expiresAtMs: record["expiresAtMs"] };
}

function parseDevices(value: unknown): readonly PairedDevice[] {
  if (!Array.isArray(value)) throw new HarnessError("E2E_DEVICE_LIST_INVALID");
  return value.map((item) => {
    const record = asRecord(item);
    if (typeof record["deviceId"] !== "string") throw new HarnessError("E2E_DEVICE_LIST_INVALID");
    return { deviceId: record["deviceId"] };
  });
}

function asRecord(value: unknown): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new HarnessError("E2E_VALUE_INVALID");
  return value as Record<string, unknown>;
}

async function abortableDelay(delayMs: number, signal: AbortSignal): Promise<void> {
  await new Promise<void>((resolveDelay, rejectDelay) => {
    const abort = (): void => {
      clearTimeout(timer);
      rejectDelay(new HarnessError("E2E_PAIRING_ABORTED"));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener("abort", abort);
      resolveDelay();
    }, delayMs);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  });
}

function sameHostState(left: E2eHostStateSnapshot["summary"], right: E2eHostStateSnapshot["summary"]): boolean {
  return left.ownerCredentialCount === right.ownerCredentialCount
    && left.deviceCount === right.deviceCount
    && left.instanceId === right.instanceId
    && left.routeId === right.routeId;
}

function isSessionId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(value);
}

function areValidHooks(hooks: readonly E2eHook[]): boolean {
  return new Set(hooks).size === hooks.length && hooks.every((hook) => E2E_HOOKS.includes(hook));
}

function errorCode(error: unknown): string {
  return error instanceof HarnessError ? error.code : "E2E_INTERNAL";
}

function isE2eCode(value: string): boolean {
  return /^E2E_[A-Z0-9_]{1,96}$/u.test(value);
}

function hasOnlyKeys(record: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(record).every((key) => allowed.includes(key));
}

function parseLockPid(value: string): number | undefined {
  try {
    const record = asRecord(JSON.parse(value));
    const pid = record["pid"];
    return typeof pid === "number" && Number.isSafeInteger(pid) && pid > 0 ? pid : undefined;
  } catch {
    return undefined;
  }
}

function processExists(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return !hasErrorCode(error, "ESRCH");
  }
}

function hasErrorCode(error: unknown, expected: string): boolean {
  return error instanceof Error && "code" in error && error.code === expected;
}

function defaultJavaHome(): string {
  const candidate = "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home";
  return candidate;
}

function sha256Text(value: string): string {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

async function sha256File(path: string): Promise<string> {
  return createHash("sha256").update(await readFile(path)).digest("hex");
}

function relativeEvidenceName(root: string, path: string): string {
  return resolve(path).slice(resolve(root).length + 1);
}

function xmlEscape(value: string): string {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll('"', "&quot;");
}
