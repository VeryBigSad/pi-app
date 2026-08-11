import type { TmuxChildProcess, TmuxProcessFactory } from "./types.js";
import { TerminalError } from "./types.js";

const CONTROL_TIMEOUT_MS = 3_000;
const MAX_CONTROL_LINE_BYTES = 1_024 * 1_024;
const MAX_CONTROL_QUEUE_BYTES = 1_024 * 1_024;
const MAX_CONTROL_QUEUE_COMMANDS = 512;
const HEX_BYTES_PER_COMMAND = 1_024;

interface PendingCommand {
  readonly line: string;
  readonly bytes: number;
  readonly resolve: () => void;
  readonly reject: (error: Error) => void;
  timer?: NodeJS.Timeout;
}

export class TmuxControlClient {
  private readonly child: TmuxChildProcess;
  private readonly queue: PendingCommand[] = [];
  private readonly paneExitListeners = new Set<() => void>();
  private pendingBytes = 0;
  private active: PendingCommand | undefined;
  private buffered = Buffer.alloc(0);
  private ready = false;
  private closed = false;
  private readonly readyPromise: Promise<void>;
  private readyResolve: (() => void) | undefined;
  private readyReject: ((error: Error) => void) | undefined;
  private readonly startupTimer: NodeJS.Timeout;

  constructor(
    factory: TmuxProcessFactory,
    args: readonly string[],
    private readonly target: string,
    signal?: AbortSignal,
  ) {
    this.child = factory.spawn(args, signal === undefined ? {} : { signal });
    this.readyPromise = new Promise<void>((resolvePromise, reject) => {
      this.readyResolve = resolvePromise;
      this.readyReject = reject;
    });
    this.startupTimer = setTimeout(() => this.fail(new TerminalError("TERMINAL_CONTROL_FAILED")), CONTROL_TIMEOUT_MS);
    this.child.onData((bytes) => this.receive(bytes));
    this.child.onExit(() => this.fail(new TerminalError("TERMINAL_CONTROL_FAILED")));
  }

  async start(): Promise<void> {
    await this.readyPromise;
  }

  async command(line: string): Promise<void> {
    if (this.closed || line.length === 0 || line.includes("\n") || line.includes("\r")) {
      throw new TerminalError(this.closed ? "TERMINAL_CLOSED" : "TERMINAL_INVALID_ARGUMENT");
    }
    const bytes = Buffer.byteLength(line, "utf8") + 1;
    if (bytes > MAX_CONTROL_QUEUE_BYTES || this.queue.length + (this.active === undefined ? 0 : 1) >= MAX_CONTROL_QUEUE_COMMANDS || this.pendingBytes + bytes > MAX_CONTROL_QUEUE_BYTES) {
      throw new TerminalError("TERMINAL_RESOURCE_EXHAUSTED");
    }
    await this.start();
    await new Promise<void>((resolvePromise, reject) => {
      const pending: PendingCommand = { line, bytes, resolve: resolvePromise, reject };
      this.queue.push(pending);
      this.pendingBytes += bytes;
      this.pump();
    });
  }

  async subscribePaneExit(listener: () => void): Promise<{ dispose(): void }> {
    this.paneExitListeners.add(listener);
    try {
      await this.command("refresh-client -B 'pimobile_exit::#{pane_dead}:#{pane_dead_status}:#{pane_dead_signal}'");
    } catch (error) {
      this.paneExitListeners.delete(listener);
      throw error;
    }
    return { dispose: () => this.paneExitListeners.delete(listener) };
  }

  async sendHex(bytes: Uint8Array): Promise<void> {
    if (bytes.byteLength === 0) return;
    for (let offset = 0; offset < bytes.byteLength; offset += HEX_BYTES_PER_COMMAND) {
      const part = bytes.subarray(offset, Math.min(bytes.byteLength, offset + HEX_BYTES_PER_COMMAND));
      if (part.some((byte) => byte > 0x7f)) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
      const hex = [...part].map((byte) => byte.toString(16).padStart(2, "0")).join(" ");
      await this.command(`send-keys -H -t ${this.target} ${hex}`);
    }
  }

  close(): void {
    if (this.closed) return;
    this.fail(new TerminalError("TERMINAL_CLOSED"));
  }

  private receive(bytes: Uint8Array): void {
    if (this.closed) return;
    this.buffered = Buffer.concat([this.buffered, Buffer.from(bytes)]);
    if (this.buffered.byteLength > MAX_CONTROL_LINE_BYTES && this.buffered.indexOf(0x0a) === -1) {
      this.fail(new TerminalError("TERMINAL_CONTROL_FAILED"));
      return;
    }
    for (;;) {
      const newline = this.buffered.indexOf(0x0a);
      if (newline < 0) return;
      const line = this.buffered.subarray(0, newline).toString("utf8");
      this.buffered = Buffer.from(this.buffered.subarray(newline + 1));
      this.receiveLine(line.endsWith("\r") ? line.slice(0, -1) : line);
    }
  }

  private receiveLine(line: string): void {
    if (!this.ready && (line.startsWith("%end ") || line.startsWith("%session-changed ") || line.startsWith("%client-session-changed "))) {
      this.ready = true;
      clearTimeout(this.startupTimer);
      this.readyResolve?.();
      this.readyResolve = undefined;
      this.readyReject = undefined;
      this.pump();
      return;
    }
    if (line.startsWith("%subscription-changed pimobile_exit ") && line.includes(" : 1:")) {
      for (const listener of this.paneExitListeners) listener();
      return;
    }
    if (line.startsWith("%error ")) {
      this.finishActive(new TerminalError("TERMINAL_CONTROL_FAILED"));
      return;
    }
    if (line.startsWith("%end ")) this.finishActive();
    if (line.startsWith("%exit")) this.fail(new TerminalError("TERMINAL_CONTROL_FAILED"));
  }

  private pump(): void {
    if (!this.ready || this.closed || this.active !== undefined) return;
    const pending = this.queue.shift();
    if (pending === undefined) return;
    this.active = pending;
    pending.timer = setTimeout(() => this.finishActive(new TerminalError("TERMINAL_CONTROL_FAILED")), CONTROL_TIMEOUT_MS);
    void this.child.write(Buffer.from(`${pending.line}\n`, "utf8")).catch(() => this.finishActive(new TerminalError("TERMINAL_CONTROL_FAILED")));
  }

  private finishActive(error?: Error): void {
    const pending = this.active;
    if (pending === undefined) return;
    this.active = undefined;
    if (pending.timer !== undefined) clearTimeout(pending.timer);
    this.pendingBytes -= pending.bytes;
    if (error === undefined) pending.resolve();
    else pending.reject(error);
    if (error instanceof TerminalError && error.code === "TERMINAL_CONTROL_FAILED") {
      this.fail(error);
      return;
    }
    this.pump();
  }

  private fail(error: Error): void {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.startupTimer);
    this.readyReject?.(error);
    this.readyResolve = undefined;
    this.readyReject = undefined;
    const active = this.active;
    this.active = undefined;
    if (active?.timer !== undefined) clearTimeout(active.timer);
    active?.reject(error);
    for (const pending of this.queue.splice(0)) {
      if (pending.timer !== undefined) clearTimeout(pending.timer);
      pending.reject(error);
    }
    this.pendingBytes = 0;
    this.paneExitListeners.clear();
    this.child.kill("SIGTERM");
  }
}
