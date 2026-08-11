import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { StrictLfJsonFramer, type PiJsonRecord } from "./lf-json-framer.js";

export type RpcProcessState = "starting" | "ready" | "stopping" | "stopped" | "faulted";

export interface RpcProcessOptions {
  readonly executable: string;
  readonly args?: readonly string[];
  readonly cwd: string;
  readonly env?: Readonly<NodeJS.ProcessEnv>;
  readonly responseTimeoutMs?: number;
  readonly stderrLimitBytes?: number;
}

interface PendingRequest {
  readonly resolve: (value: Readonly<Record<string, unknown>>) => void;
  readonly reject: (error: Error) => void;
  readonly timer: NodeJS.Timeout;
}

export class RpcProcessFault extends Error {
  readonly stderr: string;

  constructor(message: string, stderr = "", options?: ErrorOptions) {
    super(message, options);
    this.name = "RpcProcessFault";
    this.stderr = stderr;
  }
}

export class PiRpcProcess extends EventEmitter {
  private readonly options: Required<Pick<RpcProcessOptions, "responseTimeoutMs" | "stderrLimitBytes">> & RpcProcessOptions;
  private readonly framer = new StrictLfJsonFramer();
  private readonly pending = new Map<string, PendingRequest>();
  private child: ChildProcessWithoutNullStreams | undefined;
  private stderr = Buffer.alloc(0);
  private currentState: RpcProcessState = "stopped";

  constructor(options: RpcProcessOptions) {
    super();
    this.options = {
      responseTimeoutMs: options.responseTimeoutMs ?? 30_000,
      stderrLimitBytes: options.stderrLimitBytes ?? 64 * 1024,
      ...options,
    };
  }

  state(): RpcProcessState {
    return this.currentState;
  }

  start(): void {
    if (this.currentState !== "stopped") throw new RpcProcessFault("RPC process already started");
    this.currentState = "starting";
    const child = spawn(this.options.executable, [...(this.options.args ?? [])], {
      cwd: this.options.cwd,
      env: { ...process.env, ...this.options.env },
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.child = child;
    child.stdout.on("data", (chunk: Buffer) => this.onStdout(chunk));
    child.stderr.on("data", (chunk: Buffer) => this.onStderr(chunk));
    child.on("error", (error) => this.fault(new RpcProcessFault("RPC process spawn failed", this.stderrText(), { cause: error })));
    child.on("exit", (code, signal) => {
      if (this.currentState === "stopping") {
        this.currentState = "stopped";
        this.rejectPending(new RpcProcessFault("RPC process stopped", this.stderrText()));
        this.emit("stopped");
        return;
      }
      this.fault(new RpcProcessFault(`RPC process exited unexpectedly (${String(code)}, ${String(signal)})`, this.stderrText()));
    });
    this.currentState = "ready";
    this.emit("ready");
  }

  async call(command: Readonly<Record<string, unknown>>): Promise<Readonly<Record<string, unknown>>> {
    if (this.currentState !== "ready" || this.child === undefined) throw new RpcProcessFault("RPC process is not ready");
    const id = randomUUID();
    if (Object.hasOwn(command, "id")) throw new TypeError("RPC command id is host-owned");
    const payload = Buffer.from(`${JSON.stringify({ ...command, id })}\n`, "utf8");
    const response = new Promise<Readonly<Record<string, unknown>>>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new RpcProcessFault("RPC response timed out", this.stderrText()));
      }, this.options.responseTimeoutMs);
      timer.unref();
      this.pending.set(id, { resolve, reject, timer });
    });
    await new Promise<void>((resolve, reject) => {
      this.child?.stdin.write(payload, (error) => error === null || error === undefined ? resolve() : reject(error));
    }).catch((error: unknown) => {
      const request = this.pending.get(id);
      if (request !== undefined) clearTimeout(request.timer);
      this.pending.delete(id);
      throw new RpcProcessFault("RPC command write failed", this.stderrText(), { cause: error });
    });
    return response;
  }

  async stop(graceMs = 2_000): Promise<void> {
    const child = this.child;
    if (child === undefined || this.currentState === "stopped") return;
    this.currentState = "stopping";
    child.kill("SIGTERM");
    const stopped = new Promise<void>((resolve) => child.once("exit", () => resolve()));
    const timer = new Promise<void>((resolve) => setTimeout(resolve, graceMs));
    await Promise.race([stopped, timer]);
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }

  stderrText(): string {
    return this.stderr.toString("utf8");
  }

  private onStdout(chunk: Buffer): void {
    try {
      for (const record of this.framer.push(chunk)) this.routeRecord(record);
    } catch (error) {
      this.fault(new RpcProcessFault("RPC stdout framing failed", this.stderrText(), { cause: error }));
      this.child?.kill("SIGKILL");
    }
  }

  private routeRecord(record: PiJsonRecord): void {
    const id = record.value["id"];
    if (record.value["type"] === "response" && typeof id === "string") {
      const request = this.pending.get(id);
      if (request !== undefined) {
        clearTimeout(request.timer);
        this.pending.delete(id);
        request.resolve(record.value);
        return;
      }
    }
    this.emit("record", record);
  }

  private onStderr(chunk: Buffer): void {
    this.stderr = Buffer.concat([this.stderr, chunk]);
    if (this.stderr.length > this.options.stderrLimitBytes) {
      this.stderr = this.stderr.subarray(this.stderr.length - this.options.stderrLimitBytes);
    }
  }

  private fault(error: RpcProcessFault): void {
    if (this.currentState === "faulted") return;
    this.currentState = "faulted";
    this.rejectPending(error);
    this.emit("fault", error);
  }

  private rejectPending(error: Error): void {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(error);
    }
    this.pending.clear();
  }
}
