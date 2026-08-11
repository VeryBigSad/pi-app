import { constants as fsConstants } from "node:fs";
import { access } from "node:fs/promises";
import { createRequire } from "node:module";
import { arch, platform } from "node:os";
import { dirname, join } from "node:path";
import { spawn } from "node:child_process";
import type { ChildProcessWithoutNullStreams } from "node:child_process";
import type {
  PtySpawnOptions,
  TerminalPty,
  TerminalPtyFactory,
  TmuxChildProcess,
  TmuxProcessFactory,
  TmuxRunOptions,
  TmuxRunResult,
} from "./types.js";
import { TerminalError } from "./types.js";

const DEFAULT_CAPTURE_BYTES = 64 * 1_024;
const DEFAULT_PROCESS_TIMEOUT_MS = 3_000;

interface RawPty {
  readonly pid: number;
  write(data: Buffer): void;
  resize(columns: number, rows: number): void;
  pause(): void;
  resume(): void;
  kill(signal?: string): void;
  onData(listener: (data: unknown) => void): { dispose(): void };
  onExit(listener: (event: { readonly exitCode: number; readonly signal?: number }) => void): { dispose(): void };
}

interface NodePtyModule {
  spawn(executable: string, args: string[], options: Readonly<Record<string, unknown>>): RawPty;
}

export async function loadNodePtyFactory(): Promise<TerminalPtyFactory> {
  await verifyNodePtyHelper();
  let imported: unknown;
  try {
    imported = await import("node-pty");
  } catch {
    throw new TerminalError("TERMINAL_PROCESS_FAILED");
  }
  if (!isNodePtyModule(imported)) throw new TerminalError("TERMINAL_PROCESS_FAILED");
  return new NodePtyFactory(imported);
}

export class ChildProcessTmuxFactory implements TmuxProcessFactory {
  constructor(private readonly executable: string) {}

  async run(args: readonly string[], options: TmuxRunOptions = {}): Promise<TmuxRunResult> {
    if (options.signal?.aborted === true) throw new TerminalError("TERMINAL_CLOSED");
    const child = this.start(args, options);
    const captureLimit = options.captureBytes ?? DEFAULT_CAPTURE_BYTES;
    if (!Number.isSafeInteger(captureLimit) || captureLimit < 0) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
    const mode = options.captureMode ?? "head";
    let captured: Uint8Array = Buffer.alloc(0);
    let stdoutBytes = 0;
    let newlineCount = 0;
    let lastByte: number | undefined;
    child.stdout.on("data", (raw: Buffer) => {
      const bytes = Buffer.from(raw);
      stdoutBytes += bytes.byteLength;
      for (const byte of bytes) if (byte === 0x0a) newlineCount += 1;
      if (bytes.byteLength > 0) lastByte = bytes[bytes.byteLength - 1];
      captured = appendBounded(captured, bytes, captureLimit, mode);
    });
    child.stderr.resume();
    const timeoutMs = options.timeoutMs ?? DEFAULT_PROCESS_TIMEOUT_MS;
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1) {
      child.kill("SIGKILL");
      throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
    }
    return await new Promise<TmuxRunResult>((resolvePromise, reject) => {
      let settled = false;
      let timedOut = false;
      let aborted = false;
      const finish = (action: () => void): void => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        options.signal?.removeEventListener("abort", abort);
        action();
      };
      const abort = (): void => {
        aborted = true;
        child.kill("SIGTERM");
      };
      const timer = setTimeout(() => {
        timedOut = true;
        child.kill("SIGKILL");
      }, timeoutMs);
      options.signal?.addEventListener("abort", abort, { once: true });
      child.once("error", () => finish(() => reject(new TerminalError("TERMINAL_PROCESS_FAILED"))));
      child.once("close", (code, signal) => finish(() => {
        if (aborted) {
          reject(new TerminalError("TERMINAL_CLOSED"));
          return;
        }
        if (timedOut || code === null) {
          reject(new TerminalError("TERMINAL_PROCESS_FAILED"));
          return;
        }
        resolvePromise({
          exitCode: code,
          signal,
          stdout: captured,
          stdoutBytes,
          stdoutLines: stdoutBytes === 0 ? 0 : newlineCount + (lastByte === 0x0a ? 0 : 1),
          stdoutTruncated: stdoutBytes > captured.byteLength,
        });
      }));
    });
  }

  spawn(args: readonly string[], options: Omit<TmuxRunOptions, "timeoutMs" | "captureBytes" | "captureMode"> = {}): TmuxChildProcess {
    if (options.signal?.aborted === true) throw new TerminalError("TERMINAL_CLOSED");
    return new ChildProcessTmuxChild(this.start(args, options), options.signal);
  }

  private start(args: readonly string[], options: Pick<TmuxRunOptions, "cwd" | "env">): ChildProcessWithoutNullStreams {
    return spawn(this.executable, [...args], {
      shell: false,
      stdio: "pipe",
      ...(options.cwd === undefined ? {} : { cwd: options.cwd }),
      env: copyEnvironment(options.env ?? process.env),
    });
  }
}

class NodePtyFactory implements TerminalPtyFactory {
  constructor(private readonly module: NodePtyModule) {}

  spawn(executable: string, args: readonly string[], options: PtySpawnOptions): TerminalPty {
    let raw: RawPty;
    try {
      raw = this.module.spawn(executable, [...args], {
        name: options.name,
        cols: options.columns,
        rows: options.rows,
        cwd: options.cwd,
        env: copyEnvironment(options.env),
        encoding: null,
        handleFlowControl: false,
      });
    } catch {
      throw new TerminalError("TERMINAL_PROCESS_FAILED");
    }
    if (!isRawPty(raw)) throw new TerminalError("TERMINAL_PROCESS_FAILED");
    return new NodePty(raw);
  }
}

class NodePty implements TerminalPty {
  readonly pid: number;

  constructor(private readonly raw: RawPty) {
    this.pid = raw.pid;
  }

  write(bytes: Uint8Array): void {
    this.raw.write(Buffer.from(bytes));
  }

  resize(columns: number, rows: number): void {
    this.raw.resize(columns, rows);
  }

  pause(): void {
    this.raw.pause();
  }

  resume(): void {
    this.raw.resume();
  }

  kill(signal?: NodeJS.Signals): void {
    this.raw.kill(signal);
  }

  onData(listener: (bytes: Uint8Array) => void): { dispose(): void } {
    return this.raw.onData((data) => {
      if (!Buffer.isBuffer(data) && !(data instanceof Uint8Array)) {
        this.raw.kill("SIGKILL");
        return;
      }
      listener(Buffer.from(data));
    });
  }

  onExit(listener: (event: { readonly exitCode: number; readonly signal?: number }) => void): { dispose(): void } {
    return this.raw.onExit(listener);
  }
}

class ChildProcessTmuxChild implements TmuxChildProcess {
  private readonly dataListeners = new Set<(bytes: Uint8Array) => void>();
  private readonly exitListeners = new Set<(event: { readonly exitCode: number | null; readonly signal: NodeJS.Signals | null }) => void>();
  private exited = false;

  constructor(private readonly child: ChildProcessWithoutNullStreams, signal: AbortSignal | undefined) {
    child.stdout.on("data", (bytes: Buffer) => {
      const copy = Buffer.from(bytes);
      for (const listener of this.dataListeners) listener(copy);
    });
    child.stderr.resume();
    child.once("error", () => this.emitExit({ exitCode: null, signal: null }));
    child.once("close", (exitCode, exitSignal) => this.emitExit({ exitCode, signal: exitSignal }));
    if (signal !== undefined) signal.addEventListener("abort", () => child.kill("SIGTERM"), { once: true });
  }

  async write(bytes: Uint8Array): Promise<void> {
    if (this.exited || this.child.stdin.destroyed) throw new TerminalError("TERMINAL_CONTROL_FAILED");
    await new Promise<void>((resolvePromise, reject) => {
      this.child.stdin.write(Buffer.from(bytes), (error) => error === null || error === undefined
        ? resolvePromise()
        : reject(new TerminalError("TERMINAL_CONTROL_FAILED")));
    });
  }

  kill(signal: NodeJS.Signals = "SIGTERM"): void {
    if (!this.exited) this.child.kill(signal);
  }

  onData(listener: (bytes: Uint8Array) => void): { dispose(): void } {
    this.dataListeners.add(listener);
    return { dispose: () => this.dataListeners.delete(listener) };
  }

  onExit(listener: (event: { readonly exitCode: number | null; readonly signal: NodeJS.Signals | null }) => void): { dispose(): void } {
    this.exitListeners.add(listener);
    return { dispose: () => this.exitListeners.delete(listener) };
  }

  private emitExit(event: { readonly exitCode: number | null; readonly signal: NodeJS.Signals | null }): void {
    if (this.exited) return;
    this.exited = true;
    for (const listener of this.exitListeners) listener(event);
  }
}

async function verifyNodePtyHelper(): Promise<void> {
  if (platform() !== "darwin" || (arch() !== "arm64" && arch() !== "x64")) throw new TerminalError("TERMINAL_PROCESS_FAILED");
  let entry: string;
  try {
    entry = createRequire(import.meta.url).resolve("node-pty");
  } catch {
    throw new TerminalError("TERMINAL_PROCESS_FAILED");
  }
  const root = dirname(dirname(entry));
  const candidates = [
    join(root, "prebuilds", `darwin-${arch()}`, "spawn-helper"),
    join(root, "build", "Release", "spawn-helper"),
  ];
  for (const candidate of candidates) {
    try {
      await access(candidate, fsConstants.X_OK);
      return;
    } catch {
      continue;
    }
  }
  throw new TerminalError("TERMINAL_PROCESS_FAILED");
}

function appendBounded(current: Uint8Array, chunk: Uint8Array, limit: number, mode: "head" | "tail"): Uint8Array {
  if (limit === 0) return Buffer.alloc(0);
  if (mode === "head") {
    if (current.byteLength >= limit) return current;
    return Buffer.concat([current, chunk.subarray(0, limit - current.byteLength)]);
  }
  if (chunk.byteLength >= limit) return Buffer.from(chunk.subarray(chunk.byteLength - limit));
  const combined = Buffer.concat([current, chunk]);
  return combined.byteLength <= limit ? combined : Buffer.from(combined.subarray(combined.byteLength - limit));
}

function copyEnvironment(environment: Readonly<NodeJS.ProcessEnv>): NodeJS.ProcessEnv {
  return Object.fromEntries(Object.entries(environment));
}

function isNodePtyModule(value: unknown): value is NodePtyModule {
  return isRecord(value) && typeof value["spawn"] === "function";
}

function isRawPty(value: unknown): value is RawPty {
  if (!isRecord(value) || typeof value["pid"] !== "number") return false;
  return ["write", "resize", "pause", "resume", "kill", "onData", "onExit"].every((key) => typeof value[key] === "function");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
