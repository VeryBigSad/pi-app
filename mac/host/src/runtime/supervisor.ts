import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";
import { access } from "node:fs/promises";
import { createRequire } from "node:module";
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import {
  ApprovalSocketServer,
  type ApprovalOffer,
  type ApprovalSocketServerOptions,
} from "@pimobile/approval";
import {
  defaultSourceRoot,
  defaultTargetRoot,
  installPinnedPi,
  type PatchManifest,
  verifyPinnedPi,
} from "@pimobile/pi-patch";
import type { PiJsonRecord } from "../pi/lf-json-framer.js";
import { PiRpcProcess, type RpcProcessState } from "../pi/rpc-process.js";

const SOCKET_PATH_LIMIT_BYTES = 100;
const SESSION_STOP_WAIT_MS = 3_000;

export type RuntimeSupervisorState = "stopped" | "starting" | "ready" | "stopping" | "faulted";

export class RuntimeSupervisorError extends Error {
  readonly code:
    | "RUNTIME_NOT_READY"
    | "RUNTIME_STOPPING"
    | "RUNTIME_PROVISION_FAILED"
    | "RUNTIME_SESSION_FAILED"
    | "INVALID_SESSION"
    | "DUPLICATE_SESSION";

  constructor(code: RuntimeSupervisorError["code"]) {
    super(code);
    this.name = "RuntimeSupervisorError";
    this.code = code;
  }
}

export interface PiRuntimeProvisioner {
  readonly sourceRoot: () => Promise<string>;
  readonly targetRoot: () => string;
  readonly install: (sourceRoot: string, targetRoot: string) => Promise<PatchManifest>;
  readonly verify: (targetRoot: string) => Promise<PatchManifest>;
}

export interface RuntimeSupervisorOptions {
  readonly dataDirectory?: string;
  readonly approvalSocketPath?: string;
  readonly onApprovalOffer: ApprovalSocketServerOptions["onOffer"];
  readonly provisioner?: PiRuntimeProvisioner;
  readonly preloadPath?: string;
  readonly nodeExecutable?: string;
  readonly responseTimeoutMs?: number;
  readonly stderrLimitBytes?: number;
}

export interface StartRuntimeSessionOptions {
  readonly sessionId: string;
  readonly cwd: string;
  readonly sessionFile?: string;
  readonly env?: Readonly<NodeJS.ProcessEnv>;
}

export interface ApprovalDecision {
  readonly offerId: string;
  readonly operationId: string;
  readonly argumentHash: string;
  readonly connectionId: string;
  readonly decision: "allow_once" | "deny";
}

export interface RuntimeSession {
  readonly sessionId: string;
  readonly connectionId: string;
  state(): RpcProcessState;
  call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>>;
  stop(): Promise<void>;
  on(event: "record", listener: (record: PiJsonRecord) => void): this;
  on(event: "fault", listener: (fault: { readonly code: "PI_RPC_FAULT" }) => void): this;
}

export interface PinnedPiRuntime {
  readonly root: string;
  readonly manifest: PatchManifest;
  readonly installed: boolean;
}

const defaultProvisioner: PiRuntimeProvisioner = {
  sourceRoot: defaultSourceRoot,
  targetRoot: defaultTargetRoot,
  install: installPinnedPi,
  verify: verifyPinnedPi,
};

/** Supervises one shared approval broker and one patched Pi RPC child per semantic session. */
export class RuntimeSupervisor extends EventEmitter {
  private readonly options: RuntimeSupervisorOptions;
  private readonly provisioner: PiRuntimeProvisioner;
  private readonly socketPath: string;
  private readonly preloadPath: string;
  private readonly nodeExecutable: string;
  private readonly sessions = new Map<string, ManagedSession>();
  private currentState: RuntimeSupervisorState = "stopped";
  private startPromise: Promise<PinnedPiRuntime> | undefined;
  private shutdownPromise: Promise<void> | undefined;
  private approvalServer: ApprovalSocketServer | undefined;
  private runtime: PinnedPiRuntime | undefined;

  constructor(options: RuntimeSupervisorOptions) {
    super();
    this.options = options;
    this.provisioner = options.provisioner ?? defaultProvisioner;
    const dataDirectory = resolve(options.dataDirectory ?? join(homedir(), "Library", "Application Support", "PiMobile"));
    const configuredSocketPath = options.approvalSocketPath ?? join(dataDirectory, "approval.sock");
    if (!isAbsolute(configuredSocketPath) || Buffer.byteLength(configuredSocketPath, "utf8") > SOCKET_PATH_LIMIT_BYTES) {
      throw new TypeError("approval socket path is invalid");
    }
    this.socketPath = resolve(configuredSocketPath);
    this.preloadPath = resolve(options.preloadPath ?? createRequire(import.meta.url).resolve("@pimobile/preload"));
    this.nodeExecutable = options.nodeExecutable ?? process.execPath;
  }

  state(): RuntimeSupervisorState {
    return this.currentState;
  }

  approvalSocketPath(): string {
    return this.socketPath;
  }

  activeApprovalOffer(): ApprovalOffer | undefined {
    return this.approvalServer?.activeOffer();
  }

  decideApproval(input: ApprovalDecision): boolean {
    return this.approvalServer?.decide(input) ?? false;
  }

  pinnedRuntime(): PinnedPiRuntime | undefined {
    return this.runtime;
  }

  sessionCount(): number {
    return this.sessions.size;
  }

  async start(): Promise<PinnedPiRuntime> {
    if (this.currentState === "ready" && this.runtime !== undefined) return this.runtime;
    if (this.currentState === "starting" && this.startPromise !== undefined) return this.startPromise;
    if (this.currentState === "stopping") throw new RuntimeSupervisorError("RUNTIME_STOPPING");
    this.currentState = "starting";
    const startPromise = this.startInternal();
    this.startPromise = startPromise;
    try {
      return await startPromise;
    } finally {
      if (this.startPromise === startPromise) this.startPromise = undefined;
    }
  }

  async startSession(options: StartRuntimeSessionOptions): Promise<RuntimeSession> {
    const runtime = this.runtime;
    if (this.currentState !== "ready" || runtime === undefined || this.approvalServer === undefined) {
      throw new RuntimeSupervisorError("RUNTIME_NOT_READY");
    }
    validateSessionOptions(options);
    if (this.sessions.has(options.sessionId)) throw new RuntimeSupervisorError("DUPLICATE_SESSION");

    const connectionId = `session-${randomUUID()}`;
    const rpc = new PiRpcProcess({
      executable: this.nodeExecutable,
      args: [
        join(runtime.root, "dist", "cli.js"),
        "--mode", "rpc",
        ...(options.sessionFile === undefined ? ["--no-session"] : ["--session", options.sessionFile]),
      ],
      cwd: options.cwd,
      env: this.childEnvironment(options, connectionId),
      ...(this.options.responseTimeoutMs === undefined ? {} : { responseTimeoutMs: this.options.responseTimeoutMs }),
      ...(this.options.stderrLimitBytes === undefined ? {} : { stderrLimitBytes: this.options.stderrLimitBytes }),
    });
    const session = new ManagedSession(options.sessionId, connectionId, rpc, () => this.removeSession(options.sessionId, session));
    this.sessions.set(options.sessionId, session);
    rpc.on("record", (record: PiJsonRecord) => {
      emitSafely(session, "record", record);
      emitSafely(this, "session_record", { sessionId: options.sessionId, connectionId, record });
    });
    rpc.once("fault", () => {
      this.removeSession(options.sessionId, session);
      const fault = { code: "PI_RPC_FAULT" as const };
      emitSafely(session, "fault", fault);
      emitSafely(this, "session_fault", { sessionId: options.sessionId, connectionId, ...fault });
      void session.stop().catch(() => undefined);
    });
    try {
      rpc.start();
      return session;
    } catch {
      this.removeSession(options.sessionId, session);
      await session.stop();
      throw new RuntimeSupervisorError("RUNTIME_SESSION_FAILED");
    }
  }

  async shutdown(): Promise<void> {
    if (this.currentState === "stopped") return;
    if (this.currentState === "stopping" && this.shutdownPromise !== undefined) return this.shutdownPromise;
    this.currentState = "stopping";
    const shutdownPromise = this.shutdownInternal(this.startPromise);
    this.shutdownPromise = shutdownPromise;
    try {
      await shutdownPromise;
    } finally {
      if (this.shutdownPromise === shutdownPromise) this.shutdownPromise = undefined;
      this.runtime = undefined;
      this.currentState = "stopped";
      emitSafely(this, "stopped");
    }
  }

  async stop(): Promise<void> {
    await this.shutdown();
  }

  private async startInternal(): Promise<PinnedPiRuntime> {
    let server: ApprovalSocketServer | undefined;
    let serverStarted = false;
    try {
      const runtime = await this.provisionPinnedPi();
      this.ensureStarting();
      await Promise.all([access(this.preloadPath), access(join(runtime.root, "dist", "cli.js"))]);
      this.ensureStarting();
      server = new ApprovalSocketServer({
        socketPath: this.socketPath,
        onOffer: (offer) => this.deliverApprovalOffer(offer),
      });
      this.approvalServer = server;
      await server.start();
      serverStarted = true;
      this.ensureStarting();
      this.runtime = runtime;
      server = undefined;
      this.currentState = "ready";
      emitSafely(this, "ready", { piVersion: runtime.manifest.piVersion });
      return runtime;
    } catch {
      if (server !== undefined && this.approvalServer === server) this.approvalServer = undefined;
      if (serverStarted) await server?.close().catch(() => undefined);
      this.runtime = undefined;
      if (this.currentState === "starting") {
        this.currentState = "faulted";
        throw new RuntimeSupervisorError("RUNTIME_PROVISION_FAILED");
      }
      throw new RuntimeSupervisorError("RUNTIME_STOPPING");
    }
  }

  private async shutdownInternal(pendingStart: Promise<PinnedPiRuntime> | undefined): Promise<void> {
    await pendingStart?.catch(() => undefined);
    const sessions = [...this.sessions.values()];
    this.sessions.clear();
    await Promise.allSettled(sessions.map((session) => session.stop()));
    const server = this.approvalServer;
    this.approvalServer = undefined;
    if (server !== undefined) await server.close();
  }

  private async provisionPinnedPi(): Promise<PinnedPiRuntime> {
    const targetRoot = resolve(this.provisioner.targetRoot());
    try {
      const manifest = await this.provisioner.verify(targetRoot);
      return { root: targetRoot, manifest, installed: false };
    } catch {
      const sourceRoot = resolve(await this.provisioner.sourceRoot());
      const installedManifest = await this.provisioner.install(sourceRoot, targetRoot);
      const manifest = await this.provisioner.verify(targetRoot);
      if (!sameManifest(installedManifest, manifest)) throw new Error("installed Pi manifest verification failed");
      return { root: targetRoot, manifest, installed: true };
    }
  }

  private childEnvironment(options: StartRuntimeSessionOptions, connectionId: string): NodeJS.ProcessEnv {
    const inherited = { ...process.env, ...options.env };
    return {
      ...inherited,
      NODE_OPTIONS: prependImport(inherited["NODE_OPTIONS"], this.preloadPath),
      PI_MOBILE_APPROVAL_SOCKET: this.socketPath,
      PI_MOBILE_CONNECTION_ID: connectionId,
    };
  }

  private ensureStarting(): void {
    if (this.currentState !== "starting") throw new RuntimeSupervisorError("RUNTIME_STOPPING");
  }

  private deliverApprovalOffer(offer: ApprovalOffer): void {
    try {
      this.options.onApprovalOffer(offer);
    } catch {
      this.approvalServer?.decide({
        offerId: offer.offerId,
        operationId: offer.operationId,
        argumentHash: offer.argumentHash,
        connectionId: offer.connectionId,
        decision: "deny",
      });
    }
  }

  private removeSession(sessionId: string, session: ManagedSession): void {
    if (this.sessions.get(sessionId) === session) this.sessions.delete(sessionId);
  }
}

class ManagedSession extends EventEmitter implements RuntimeSession {
  private stopPromise: Promise<void> | undefined;

  constructor(
    readonly sessionId: string,
    readonly connectionId: string,
    private readonly rpc: PiRpcProcess,
    private readonly onStop: () => void,
  ) {
    super();
  }

  state(): RpcProcessState {
    return this.rpc.state();
  }

  async call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>> {
    try {
      return await this.rpc.call(command);
    } catch {
      throw new RuntimeSupervisorError("RUNTIME_SESSION_FAILED");
    }
  }

  stop(): Promise<void> {
    this.stopPromise ??= this.stopInternal();
    return this.stopPromise;
  }

  private async stopInternal(): Promise<void> {
    try {
      await this.rpc.stop();
      await waitForTerminalState(this.rpc);
    } finally {
      this.onStop();
    }
  }
}

function validateSessionOptions(options: StartRuntimeSessionOptions): void {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/u.test(options.sessionId) || !isAbsolute(options.cwd)) {
    throw new RuntimeSupervisorError("INVALID_SESSION");
  }
  if (options.sessionFile !== undefined && !isAbsolute(options.sessionFile)) throw new RuntimeSupervisorError("INVALID_SESSION");
}

function prependImport(existing: string | undefined, preloadPath: string): string {
  const preload = `--import=${pathToFileURL(preloadPath).href}`;
  return existing === undefined || existing.trim().length === 0 ? preload : `${preload} ${existing}`;
}

function sameManifest(left: PatchManifest, right: PatchManifest): boolean {
  return left.piVersion === right.piVersion &&
    left.originalAgentSessionSha256 === right.originalAgentSessionSha256 &&
    left.patchedAgentSessionSha256 === right.patchedAgentSessionSha256 &&
    left.policyHookKey === right.policyHookKey;
}

function emitSafely(emitter: EventEmitter, eventName: string, ...argumentsValue: readonly unknown[]): void {
  try {
    emitter.emit(eventName, ...argumentsValue);
  } catch {
    return;
  }
}

async function waitForTerminalState(rpc: PiRpcProcess): Promise<void> {
  if (rpc.state() === "stopped" || rpc.state() === "faulted") return;
  await new Promise<void>((resolvePromise) => {
    let settled = false;
    const finish = (): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      rpc.removeListener("stopped", finish);
      rpc.removeListener("fault", finish);
      resolvePromise();
    };
    const timer = setTimeout(finish, SESSION_STOP_WAIT_MS);
    timer.unref();
    rpc.once("stopped", finish);
    rpc.once("fault", finish);
    if (rpc.state() === "stopped" || rpc.state() === "faulted") finish();
  });
}
