import { randomUUID } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { access, chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { arch as hostArch, platform as hostPlatform, release as hostRelease } from "node:os";
import { delimiter, isAbsolute, join } from "node:path";
import { TmuxControlClient } from "./control-client.js";
import { captureTerminalHistory } from "./history.js";
import { encodeKittyKey, encodeTextKey } from "./key-encoder.js";
import { ChildProcessTmuxFactory, loadNodePtyFactory } from "./process.js";
import type {
  AttachTerminalOptions,
  CreateTerminalSessionOptions,
  TerminalHistoryRequest,
  TerminalHistoryResult,
  TerminalInput,
  TerminalKeyInput,
  TerminalMarker,
  TerminalOutput,
  TerminalProcessExit,
  TerminalPty,
  TerminalPtyFactory,
  TmuxProcessFactory,
} from "./types.js";
import {
  MAX_PENDING_INPUT_BYTES,
  MAX_PENDING_INPUTS,
  MAX_PENDING_OUTPUT_BYTES,
  MAX_PENDING_OUTPUTS,
  MAX_TERMINAL_COLUMNS,
  MAX_TERMINAL_DATA_BYTES,
  MAX_TERMINAL_ROWS,
  TerminalError,
} from "./types.js";

const TMUX_MINIMUM_MAJOR = 3;
const TMUX_MINIMUM_MINOR = 5;
const MACOS_14_DARWIN_MAJOR = 23;
const TMUX_SOCKET_PATH_LIMIT_BYTES = 100;
const PROCESS_TIMEOUT_MS = 3_000;
const OUTPUT_STALL_MS = 10_000;
const EXACT_EXECUTABLE_WRAPPER = "/usr/bin/env";
const UINT64_MAX = (1n << 64n) - 1n;

const tmuxConfig = [
  "set -g status off",
  "set -g remain-on-exit on",
  "set -g exit-empty off",
  "set -g history-limit 5000",
  "set -g default-terminal tmux-256color",
  "set -g extended-keys on",
  "set -g extended-keys-format csi-u",
  "set -g focus-events on",
  "",
].join("\n");

export interface TerminalBackendOptions {
  readonly tmux: TmuxProcessFactory;
  readonly pty: TerminalPtyFactory;
  readonly tmuxVersion: string;
  readonly tmuxExecutable?: string;
  readonly runtimeParent?: string;
  readonly now?: () => Date;
  readonly randomId?: () => string;
}

export interface LocalTerminalBackendOptions {
  readonly runtimeParent?: string;
  readonly tmuxExecutable?: string;
  readonly path?: string;
  readonly platform?: NodeJS.Platform;
  readonly arch?: string;
  readonly release?: string;
  readonly now?: () => Date;
}

export type LocalTerminalBackendResult =
  | { readonly state: "supported"; readonly tmuxVersion: string; readonly backend: TerminalBackend }
  | { readonly state: "unsupported"; readonly code: "HOST_UNSUPPORTED" | "TMUX_NOT_FOUND" | "TMUX_VERSION_UNSUPPORTED" | "NODE_PTY_UNAVAILABLE" };

export async function createLocalTerminalBackend(options: LocalTerminalBackendOptions = {}): Promise<LocalTerminalBackendResult> {
  const platform = options.platform ?? hostPlatform();
  const arch = options.arch ?? hostArch();
  const release = options.release ?? hostRelease();
  if (platform !== "darwin" || arch !== "arm64" || darwinMajor(release) < MACOS_14_DARWIN_MAJOR) {
    return { state: "unsupported", code: "HOST_UNSUPPORTED" };
  }
  const executable = options.tmuxExecutable ?? await findExecutable("tmux", options.path ?? process.env["PATH"] ?? "");
  if (executable === undefined) return { state: "unsupported", code: "TMUX_NOT_FOUND" };
  const tmux = new ChildProcessTmuxFactory(executable);
  let versionResult;
  try {
    versionResult = await tmux.run(["-V"], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 128, captureMode: "head" });
  } catch {
    return { state: "unsupported", code: "TMUX_NOT_FOUND" };
  }
  const tmuxVersion = new TextDecoder().decode(versionResult.stdout).trim();
  if (versionResult.exitCode !== 0 || !supportedTmuxVersion(tmuxVersion)) {
    return { state: "unsupported", code: "TMUX_VERSION_UNSUPPORTED" };
  }
  let pty: TerminalPtyFactory;
  try {
    pty = await loadNodePtyFactory();
  } catch {
    return { state: "unsupported", code: "NODE_PTY_UNAVAILABLE" };
  }
  return {
    state: "supported",
    tmuxVersion,
    backend: new TerminalBackend({
      tmux,
      pty,
      tmuxVersion,
      tmuxExecutable: executable,
      ...(options.runtimeParent === undefined ? {} : { runtimeParent: options.runtimeParent }),
      ...(options.now === undefined ? {} : { now: options.now }),
    }),
  };
}

export class TerminalBackend {
  private readonly sessions = new Map<string, TerminalSession>();
  private readonly now: () => Date;
  private readonly randomId: () => string;
  private runtimeDirectoryValue: string | undefined;
  private socketPathValue: string | undefined;
  private configPathValue: string | undefined;
  private startPromise: Promise<void> | undefined;
  private stopPromise: Promise<void> | undefined;
  private stopped = false;

  constructor(private readonly options: TerminalBackendOptions) {
    if (!supportedTmuxVersion(options.tmuxVersion)) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
    this.now = options.now ?? (() => new Date());
    this.randomId = options.randomId ?? randomUUID;
  }

  async start(): Promise<void> {
    if (this.stopped) throw new TerminalError("TERMINAL_CLOSED");
    if (this.runtimeDirectoryValue !== undefined) return;
    if (this.startPromise !== undefined) return this.startPromise;
    const pending = this.startInternal();
    this.startPromise = pending;
    try {
      await pending;
    } finally {
      if (this.startPromise === pending) this.startPromise = undefined;
    }
  }

  async createSession(options: CreateTerminalSessionOptions): Promise<TerminalSession> {
    validateCreateOptions(options);
    if (this.sessions.has(options.sessionId)) throw new TerminalError("TERMINAL_ALREADY_EXISTS");
    await this.start();
    const name = `pm${this.randomId().replaceAll("-", "")}`;
    if (!/^pm[0-9A-Za-z]+$/.test(name)) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
    const prefix = this.tmuxPrefix();
    const target = `${name}:0.0`;
    const environmentArguments = environmentArgs(options.env);
    const result = await this.options.tmux.run([
      ...prefix,
      "new-session",
      "-d",
      "-s",
      name,
      "-x",
      String(options.columns),
      "-y",
      String(options.rows),
      "-c",
      options.cwd,
      "--",
      EXACT_EXECUTABLE_WRAPPER,
      "--",
      "/bin/sleep",
      "2147483647",
    ], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 0, env: tmuxEnvironment() });
    if (result.exitCode !== 0) throw new TerminalError("TERMINAL_PROCESS_FAILED");
    await chmod(this.socketPath(), 0o700);
    const controller = new AbortController();
    const control = new TmuxControlClient(
      this.options.tmux,
      [...prefix, "-C", "attach-session", "-t", name],
      target,
      controller.signal,
    );
    try {
      await control.start();
      const session = new TerminalSession({
        sessionId: options.sessionId,
        name,
        target,
        prefix,
        cwd: options.cwd,
        tmuxExecutable: this.options.tmuxExecutable ?? "tmux",
        tmux: this.options.tmux,
        ptyFactory: this.options.pty,
        control,
        controller,
        now: this.now,
        onClosed: () => this.sessions.delete(options.sessionId),
      });
      await session.startExitWatcher();
      const respawn = await this.options.tmux.run([
        ...prefix,
        "respawn-pane",
        "-k",
        "-c",
        options.cwd,
        ...environmentArguments,
        "-t",
        target,
        "--",
        EXACT_EXECUTABLE_WRAPPER,
        "--",
        options.command,
        ...(options.args ?? []),
      ], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 0, env: tmuxEnvironment() });
      if (respawn.exitCode !== 0) throw new TerminalError("TERMINAL_PROCESS_FAILED");
      this.sessions.set(options.sessionId, session);
      return session;
    } catch (error) {
      control.close();
      controller.abort();
      await this.options.tmux.run([...prefix, "kill-session", "-t", name], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 0 }).catch(() => undefined);
      throw error instanceof TerminalError ? error : new TerminalError("TERMINAL_CONTROL_FAILED");
    }
  }

  session(sessionId: string): TerminalSession | undefined {
    return this.sessions.get(sessionId);
  }

  runtimeDirectory(): string | undefined {
    return this.runtimeDirectoryValue;
  }

  socketPath(): string {
    if (this.socketPathValue === undefined) throw new TerminalError("TERMINAL_CLOSED");
    return this.socketPathValue;
  }

  async stop(): Promise<void> {
    if (this.stopPromise !== undefined) return this.stopPromise;
    const pending = this.stopInternal();
    this.stopPromise = pending;
    try {
      await pending;
    } finally {
      if (this.stopPromise === pending) this.stopPromise = undefined;
    }
  }

  private async startInternal(): Promise<void> {
    const parent = this.options.runtimeParent ?? "/tmp";
    await mkdir(parent, { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(parent, "pimobile-terminal-"));
    try {
      await chmod(directory, 0o700);
      const socket = join(directory, "tmux.sock");
      if (Buffer.byteLength(socket, "utf8") > TMUX_SOCKET_PATH_LIMIT_BYTES) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
      const config = join(directory, "tmux.conf");
      await writeFile(config, tmuxConfig, { encoding: "utf8", mode: 0o600, flag: "wx" });
      this.runtimeDirectoryValue = directory;
      this.socketPathValue = socket;
      this.configPathValue = config;
    } catch (error) {
      await rm(directory, { recursive: true, force: true });
      throw error;
    }
  }

  private async stopInternal(): Promise<void> {
    if (this.stopped) return;
    this.stopped = true;
    const starting = this.startPromise;
    if (starting !== undefined) await starting.catch(() => undefined);
    await Promise.allSettled([...this.sessions.values()].map((session) => session.shutdown()));
    this.sessions.clear();
    if (this.socketPathValue !== undefined) {
      await this.options.tmux.run([...this.tmuxPrefix(), "kill-server"], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 0 }).catch(() => undefined);
    }
    if (this.runtimeDirectoryValue !== undefined) await rm(this.runtimeDirectoryValue, { recursive: true, force: true });
    this.runtimeDirectoryValue = undefined;
    this.socketPathValue = undefined;
    this.configPathValue = undefined;
  }

  private tmuxPrefix(): readonly string[] {
    if (this.configPathValue === undefined || this.socketPathValue === undefined) throw new TerminalError("TERMINAL_CLOSED");
    return ["-f", this.configPathValue, "-S", this.socketPathValue];
  }
}

interface TerminalSessionConstruction {
  readonly sessionId: string;
  readonly name: string;
  readonly target: string;
  readonly prefix: readonly string[];
  readonly cwd: string;
  readonly tmuxExecutable: string;
  readonly tmux: TmuxProcessFactory;
  readonly ptyFactory: TerminalPtyFactory;
  readonly control: TmuxControlClient;
  readonly controller: AbortController;
  readonly now: () => Date;
  readonly onClosed: () => void;
}

export class TerminalSession {
  readonly sessionId: string;
  private readonly exitListeners = new Set<(exit: TerminalProcessExit) => void>();
  private readonly exitPromise: Promise<TerminalProcessExit>;
  private resolveExit: ((exit: TerminalProcessExit) => void) | undefined;
  private active: TerminalAttachment | undefined;
  private generation = 0n;
  private exit: TerminalProcessExit | undefined;
  private exitSubscription: { dispose(): void } | undefined;
  private closed = false;

  constructor(private readonly construction: TerminalSessionConstruction) {
    this.sessionId = construction.sessionId;
    this.exitPromise = new Promise<TerminalProcessExit>((resolvePromise) => {
      this.resolveExit = resolvePromise;
    });
  }

  async attach(options: AttachTerminalOptions): Promise<TerminalAttachment> {
    validateDimensions(options.columns, options.rows);
    if (this.closed) throw new TerminalError("TERMINAL_CLOSED");
    await this.active?.detach();
    if (this.generation >= UINT64_MAX) throw new TerminalError("TERMINAL_RESOURCE_EXHAUSTED");
    this.generation += 1n;
    const pty = this.construction.ptyFactory.spawn(
      this.construction.tmuxExecutable,
      [...this.construction.prefix, "attach-session", "-t", this.construction.name],
      {
        name: "xterm-256color",
        columns: options.columns,
        rows: options.rows,
        cwd: this.construction.cwd,
        env: tmuxEnvironment(),
      },
    );
    const generation = this.generation;
    const attachment = new TerminalAttachment({
      generation,
      reconnect: this.generation > 1n,
      pty,
      control: this.construction.control,
      tmux: this.construction.tmux,
      prefix: this.construction.prefix,
      target: this.construction.target,
      options,
      isCurrent: (): boolean => this.active?.terminalGeneration === generation && !this.closed,
      onDetached: () => {
        if (this.active?.terminalGeneration === generation) this.active = undefined;
      },
    });
    this.active = attachment;
    try {
      await attachment.start();
      if (this.exit !== undefined) await attachment.emitProcessExit();
      return attachment;
    } catch {
      await attachment.detach();
      throw new TerminalError("TERMINAL_PROCESS_FAILED");
    }
  }

  async history(request: TerminalHistoryRequest): Promise<TerminalHistoryResult> {
    if (this.closed) throw new TerminalError("TERMINAL_CLOSED");
    if (request.terminalGeneration !== this.active?.terminalGeneration) {
      throw new TerminalError("TERMINAL_NOT_ATTACHED");
    }
    return await captureTerminalHistory(
      this.construction.tmux,
      this.construction.prefix,
      this.construction.target,
      request,
      this.construction.now,
    );
  }

  waitForExit(): Promise<TerminalProcessExit> {
    return this.exitPromise;
  }

  onExit(listener: (exit: TerminalProcessExit) => void): { dispose(): void } {
    this.exitListeners.add(listener);
    const exit = this.exit;
    if (exit !== undefined) queueMicrotask(() => listener(exit));
    return { dispose: () => this.exitListeners.delete(listener) };
  }

  async startExitWatcher(): Promise<void> {
    if (this.closed || this.exitSubscription !== undefined) return;
    this.exitSubscription = await this.construction.control.subscribePaneExit(() => {
      if (this.closed || this.exit !== undefined) return;
      void this.readProcessExit();
    });
  }

  async cancel(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.construction.controller.abort();
    this.exitSubscription?.dispose();
    this.exitSubscription = undefined;
    await this.active?.detach();
    this.construction.control.close();
    await this.construction.tmux.run(
      [...this.construction.prefix, "kill-session", "-t", this.construction.name],
      { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 0 },
    ).catch(() => undefined);
    this.setExit({ exitCode: null, signal: null, cancelled: true });
    this.construction.onClosed();
  }

  async shutdown(): Promise<void> {
    await this.cancel();
  }

  private async readProcessExit(): Promise<void> {
    const result = await this.construction.tmux.run([
      ...this.construction.prefix,
      "display-message",
      "-p",
      "-t",
      this.construction.target,
      "-F",
      "#{pane_dead}:#{pane_dead_status}:#{pane_dead_signal}",
    ], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 128, captureMode: "head" }).catch(() => undefined);
    if (this.closed || this.exit !== undefined || result?.exitCode !== 0) return;
    const values = new TextDecoder().decode(result.stdout).trim().split(":");
    if (values[0] !== "1") return;
    this.setExit({
      exitCode: parseOptionalInteger(values[1]),
      signal: parseOptionalInteger(values[2]),
      cancelled: false,
    });
    await this.active?.emitProcessExit();
  }

  private setExit(exit: TerminalProcessExit): void {
    if (this.exit !== undefined) return;
    this.exit = exit;
    this.resolveExit?.(exit);
    this.resolveExit = undefined;
    for (const listener of this.exitListeners) listener(exit);
  }
}

interface TerminalAttachmentConstruction {
  readonly generation: bigint;
  readonly reconnect: boolean;
  readonly pty: TerminalPty;
  readonly control: TmuxControlClient;
  readonly tmux: TmuxProcessFactory;
  readonly prefix: readonly string[];
  readonly target: string;
  readonly options: AttachTerminalOptions;
  readonly isCurrent: () => boolean;
  readonly onDetached: () => void;
}

interface PendingOutput {
  readonly bytes: Buffer;
}

export class TerminalAttachment {
  readonly terminalGeneration: bigint;
  private readonly outputQueue: PendingOutput[] = [];
  private outputBytes = 0;
  private outputSequence = 0n;
  private inputSequence = 0n;
  private inputTail = Promise.resolve();
  private inputBytes = 0;
  private inputCount = 0;
  private pumpingOutput = false;
  private paused = true;
  private detached = false;
  private detachPromise: Promise<void> | undefined;
  private readonly dataSubscription: { dispose(): void };
  private readonly exitSubscription: { dispose(): void };

  constructor(private readonly construction: TerminalAttachmentConstruction) {
    this.terminalGeneration = construction.generation;
    construction.pty.pause();
    this.dataSubscription = construction.pty.onData((bytes) => this.receiveOutput(bytes));
    this.exitSubscription = construction.pty.onExit(() => {
      if (this.detached) return;
      void this.reset("renderer_restart");
    });
  }

  async start(): Promise<void> {
    await this.emitMarker({
      type: this.construction.reconnect ? "reconnected" : "attached",
      terminalGeneration: this.terminalGeneration,
      reason: this.construction.reconnect ? "reconnect" : "initial",
    });
    await this.construction.control.command(`refresh-client -C ${String(this.construction.options.columns)},${String(this.construction.options.rows)}`);
    this.construction.pty.resize(this.construction.options.columns, this.construction.options.rows);
    this.paused = false;
    this.construction.pty.resume();
  }

  send(input: TerminalInput): Promise<void> {
    validateTerminalInput(input);
    return this.enqueueInput(input.terminalGeneration, input.sequence, input.bytes.byteLength, () => {
      this.construction.pty.write(input.bytes);
      return Promise.resolve();
    });
  }

  sendKey(input: TerminalKeyInput): Promise<void> {
    validateTerminalSequence(input.terminalGeneration, input.sequence);
    const bytes = input.action === "text" ? encodeTextKey(input) : encodeKittyKey(input);
    return this.enqueueInput(input.terminalGeneration, input.sequence, bytes.byteLength, async () => {
      if (input.action === "text") this.construction.pty.write(bytes);
      else await this.construction.control.sendHex(bytes);
    });
  }

  async resize(terminalGeneration: bigint, columns: number, rows: number): Promise<void> {
    validateDimensions(columns, rows);
    this.assertCurrent(terminalGeneration);
    await this.inputTail;
    this.assertCurrent(terminalGeneration);
    this.construction.pty.resize(columns, rows);
    const result = await this.construction.tmux.run([
      ...this.construction.prefix,
      "resize-window",
      "-x",
      String(columns),
      "-y",
      String(rows),
      "-t",
      this.construction.target,
    ], { timeoutMs: PROCESS_TIMEOUT_MS, captureBytes: 0 });
    if (result.exitCode !== 0) throw new TerminalError("TERMINAL_CONTROL_FAILED");
    await this.construction.control.command(`refresh-client -C ${String(columns)},${String(rows)}`);
  }

  detach(): Promise<void> {
    if (this.detachPromise !== undefined) return this.detachPromise;
    const pending = this.detachInternal();
    this.detachPromise = pending;
    return pending;
  }

  async emitProcessExit(): Promise<void> {
    if (this.detached) return;
    await this.emitMarker({
      type: "process_exit",
      terminalGeneration: this.terminalGeneration,
      reason: "process_exit",
    });
  }

  private enqueueInput(
    generation: bigint,
    sequence: bigint,
    size: number,
    operation: () => Promise<void>,
  ): Promise<void> {
    try {
      this.assertCurrent(generation);
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new TerminalError("TERMINAL_CONTROL_FAILED"));
    }
    if (sequence !== this.inputSequence) {
      void this.reset("sequence_gap");
      return Promise.reject(new TerminalError("TERMINAL_RESET_REQUIRED"));
    }
    if (this.inputCount >= MAX_PENDING_INPUTS || this.inputBytes + size > MAX_PENDING_INPUT_BYTES) {
      void this.reset("resource_exhausted");
      return Promise.reject(new TerminalError("TERMINAL_RESOURCE_EXHAUSTED"));
    }
    this.inputSequence += 1n;
    this.inputCount += 1;
    this.inputBytes += size;
    const pending = this.inputTail.then(async () => {
      this.assertCurrent(generation);
      await operation();
    });
    this.inputTail = pending.catch(() => undefined).then(() => undefined);
    return pending.finally(() => {
      this.inputCount -= 1;
      this.inputBytes -= size;
    }).catch((error: unknown) => {
      if (!this.detached) void this.reset("renderer_restart");
      throw error instanceof TerminalError ? error : new TerminalError("TERMINAL_CONTROL_FAILED");
    });
  }

  private receiveOutput(bytes: Uint8Array): void {
    if (this.detached || !this.construction.isCurrent() || bytes.byteLength === 0) return;
    for (let offset = 0; offset < bytes.byteLength; offset += MAX_TERMINAL_DATA_BYTES) {
      const copy = Buffer.from(bytes.subarray(offset, Math.min(bytes.byteLength, offset + MAX_TERMINAL_DATA_BYTES)));
      if (this.outputQueue.length >= MAX_PENDING_OUTPUTS || this.outputBytes + copy.byteLength > MAX_PENDING_OUTPUT_BYTES) {
        void this.reset("resource_exhausted");
        return;
      }
      this.outputQueue.push({ bytes: copy });
      this.outputBytes += copy.byteLength;
    }
    if (!this.paused) {
      this.paused = true;
      this.construction.pty.pause();
    }
    if (!this.pumpingOutput) void this.pumpOutput();
  }

  private async pumpOutput(): Promise<void> {
    if (this.pumpingOutput) return;
    this.pumpingOutput = true;
    try {
      while (!this.detached && this.construction.isCurrent()) {
        const pending = this.outputQueue.shift();
        if (pending === undefined) break;
        this.outputBytes -= pending.bytes.byteLength;
        if (this.outputSequence > UINT64_MAX) {
          await this.reset("resource_exhausted");
          return;
        }
        const output: TerminalOutput = {
          terminalGeneration: this.terminalGeneration,
          sequence: this.outputSequence,
          bytes: Buffer.from(pending.bytes),
        };
        this.outputSequence += 1n;
        await withTimeout(
          Promise.resolve(this.construction.options.onOutput(output)),
          OUTPUT_STALL_MS,
          new TerminalError("TERMINAL_RESOURCE_EXHAUSTED"),
        );
      }
      if (!this.detached && this.outputQueue.length === 0 && this.paused) {
        this.paused = false;
        this.construction.pty.resume();
      }
    } catch (error) {
      await this.reset(error instanceof TerminalError && error.code === "TERMINAL_RESOURCE_EXHAUSTED" ? "resource_exhausted" : "renderer_restart");
    } finally {
      this.pumpingOutput = false;
      if (!this.detached && this.outputQueue.length > 0) void this.pumpOutput();
    }
  }

  private async reset(reason: "sequence_gap" | "renderer_restart" | "resource_exhausted"): Promise<void> {
    if (this.detached) return;
    await this.emitMarker({
      type: "reset_required",
      terminalGeneration: this.terminalGeneration,
      reason,
    }).catch(() => undefined);
    await this.detach();
  }

  private async detachInternal(): Promise<void> {
    if (this.detached) return;
    this.detached = true;
    this.dataSubscription.dispose();
    this.exitSubscription.dispose();
    this.outputQueue.splice(0);
    this.outputBytes = 0;
    this.construction.pty.kill("SIGHUP");
    this.construction.onDetached();
    await this.inputTail;
  }

  private assertCurrent(generation: bigint): void {
    if (this.detached || !this.construction.isCurrent()) throw new TerminalError("TERMINAL_NOT_ATTACHED");
    if (generation !== this.terminalGeneration) throw new TerminalError("TERMINAL_RESET_REQUIRED");
  }

  private async emitMarker(marker: TerminalMarker): Promise<void> {
    if (this.construction.options.onMarker === undefined) return;
    await withTimeout(
      Promise.resolve(this.construction.options.onMarker(marker)),
      PROCESS_TIMEOUT_MS,
      new TerminalError("TERMINAL_CONTROL_FAILED"),
    );
  }
}

function validateCreateOptions(options: CreateTerminalSessionOptions): void {
  if (
    options.sessionId.length === 0
    || options.sessionId.length > 128
    || options.sessionId.includes("\u0000")
    || !isAbsolute(options.command)
    || options.command.includes("\u0000")
    || !isAbsolute(options.cwd)
    || options.cwd.includes("\u0000")
    || (options.args ?? []).some((argument) => argument.includes("\u0000"))
  ) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
  validateDimensions(options.columns, options.rows);
  environmentArgs(options.env);
}

function validateTerminalInput(input: TerminalInput): void {
  validateTerminalSequence(input.terminalGeneration, input.sequence);
  if (input.bytes.byteLength > MAX_TERMINAL_DATA_BYTES) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
}

function validateTerminalSequence(generation: bigint, sequence: bigint): void {
  if (generation < 0n || generation > UINT64_MAX || sequence < 0n || sequence > UINT64_MAX) {
    throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
  }
}

function validateDimensions(columns: number, rows: number): void {
  if (
    !Number.isSafeInteger(columns)
    || columns < 1
    || columns > MAX_TERMINAL_COLUMNS
    || !Number.isSafeInteger(rows)
    || rows < 1
    || rows > MAX_TERMINAL_ROWS
  ) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
}

function environmentArgs(environment: Readonly<NodeJS.ProcessEnv> | undefined): string[] {
  if (environment === undefined) return [];
  const args: string[] = [];
  for (const [key, value] of Object.entries(environment)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(key) || value?.includes("\u0000") === true) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
    if (value !== undefined) args.push("-e", `${key}=${value}`);
  }
  return args;
}

function tmuxEnvironment(): NodeJS.ProcessEnv {
  const environment: NodeJS.ProcessEnv = { ...process.env, TERM: "xterm-256color" };
  delete environment["TMUX"];
  delete environment["TMUX_PANE"];
  return environment;
}

function supportedTmuxVersion(version: string): boolean {
  const match = /^tmux (\d+)\.(\d+)(?:[a-z]|-.*)?$/.exec(version.trim());
  if (match === null) return false;
  const major = Number(match[1]);
  const minor = Number(match[2]);
  return major > TMUX_MINIMUM_MAJOR || (major === TMUX_MINIMUM_MAJOR && minor >= TMUX_MINIMUM_MINOR);
}

function darwinMajor(release: string): number {
  const value = Number(release.split(".")[0]);
  return Number.isSafeInteger(value) ? value : -1;
}

async function findExecutable(name: string, path: string): Promise<string | undefined> {
  for (const directory of path.split(delimiter)) {
    if (directory.length === 0) continue;
    const candidate = join(directory, name);
    try {
      await access(candidate, fsConstants.X_OK);
      return candidate;
    } catch {
      continue;
    }
  }
  return undefined;
}

async function withTimeout<T>(promise: Promise<T>, milliseconds: number, error: Error): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      promise,
      new Promise<never>((_resolve, reject) => {
        timer = setTimeout(() => reject(error), milliseconds);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

function parseOptionalInteger(value: string | undefined): number | null {
  if (value === undefined || value.length === 0) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}
