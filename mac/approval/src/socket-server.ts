import { createHash } from "node:crypto";
import { chmod, lstat, mkdir, unlink } from "node:fs/promises";
import { createConnection, createServer, type Server, type Socket } from "node:net";
import { dirname, isAbsolute } from "node:path";
import { performance } from "node:perf_hooks";
import { ApprovalBroker, type ApprovalOffer, type ApprovalResult, type BrokerClock } from "./broker.js";
import { classify, type PolicyOperation } from "./classifier.js";

const MAX_WIRE_BYTES = 64 << 10;
const MAX_HOOK_MS = 150_000;
const REQUEST_READ_MS = 5_000;

export interface ApprovalSocketServerOptions {
  readonly socketPath: string;
  readonly onOffer: (offer: ApprovalOffer) => void;
  readonly clock?: BrokerClock;
}

interface WireRequest {
  readonly v: 1;
  readonly type: "approval.request";
  readonly operationId: string;
  readonly connectionId: string;
  readonly argumentHash: string;
  readonly operation: string;
  readonly policyOperation: PolicyOperation;
  readonly cwd?: string;
  readonly timeoutMs: number;
}

export class ApprovalSocketServer {
  private readonly socketPath: string;
  private readonly clock: BrokerClock;
  private readonly broker: ApprovalBroker;
  private server: Server | undefined;

  constructor(options: ApprovalSocketServerOptions) {
    if (!isAbsolute(options.socketPath)) throw new TypeError("approval socket path must be absolute");
    this.socketPath = options.socketPath;
    this.clock = options.clock ?? { monotonicMs: () => performance.now(), wallMs: () => Date.now() };
    this.broker = new ApprovalBroker({ onOffer: options.onOffer, clock: this.clock });
  }

  async start(): Promise<void> {
    if (this.server !== undefined) throw new Error("approval socket server already started");
    await mkdir(dirname(this.socketPath), { recursive: true, mode: 0o700 });
    await removeStaleSocket(this.socketPath);
    const server = createServer((socket) => this.handle(socket));
    server.maxConnections = 32;
    await new Promise<void>((resolvePromise, reject) => {
      server.once("error", reject);
      server.listen(this.socketPath, () => {
        server.removeListener("error", reject);
        resolvePromise();
      });
    });
    await chmod(this.socketPath, 0o600);
    this.server = server;
  }

  async close(): Promise<void> {
    const server = this.server;
    this.server = undefined;
    if (server !== undefined) {
      await new Promise<void>((resolvePromise, reject) => server.close((error) => error === undefined ? resolvePromise() : reject(error)));
    }
    await unlink(this.socketPath).catch((error: unknown) => {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    });
  }

  decide(input: Parameters<ApprovalBroker["decide"]>[0]): boolean {
    return this.broker.decide(input);
  }

  activeOffer(): ApprovalOffer | undefined {
    return this.broker.activeOffer();
  }

  private handle(socket: Socket): void {
    let pending = Buffer.alloc(0);
    let connectionId: string | undefined;
    let completed = false;
    const timer = setTimeout(() => socket.destroy(), REQUEST_READ_MS);
    socket.on("data", (chunk: Buffer) => {
      pending = Buffer.concat([pending, chunk]);
      if (pending.length > MAX_WIRE_BYTES) {
        socket.destroy();
        return;
      }
      const newline = pending.indexOf(0x0a);
      if (newline < 0) return;
      clearTimeout(timer);
      socket.pause();
      if (newline !== pending.length - 1) {
        socket.destroy();
        return;
      }
      let request: WireRequest;
      try {
        request = parseRequest(JSON.parse(pending.subarray(0, newline).toString("utf8")));
      } catch {
        socket.destroy();
        return;
      }
      connectionId = request.connectionId;
      const classification = classify(request.policyOperation);
      const normalized = classification.disposition === "allow" ? normalizeAllowed(request.policyOperation) : classification.normalized;
      const argumentHash = createHash("sha256").update(normalized).digest("hex");
      if (argumentHash !== request.argumentHash) {
        socket.destroy();
        return;
      }
      const result = classification.disposition === "allow"
        ? Promise.resolve<ApprovalResult>({ allowed: true, offerId: "local-safe" })
        : this.broker.request({
            operationId: request.operationId,
            connectionId: request.connectionId,
            argumentHash,
            operation: request.operation,
            ...(request.cwd === undefined ? {} : { cwd: request.cwd }),
            reasons: classification.reasons,
            hookDeadlineMs: this.clock.monotonicMs() + Math.min(request.timeoutMs, MAX_HOOK_MS),
          });
      void result.then((value) => {
        completed = true;
        socket.end(`${JSON.stringify({ v: 1, type: "approval.result", operationId: request.operationId, argumentHash, ...value })}\n`);
      }, () => socket.destroy());
    });
    socket.once("close", () => {
      clearTimeout(timer);
      if (!completed && connectionId !== undefined) this.broker.cancelConnection(connectionId);
    });
    socket.once("error", () => undefined);
  }
}

function parseRequest(value: unknown): WireRequest {
  if (typeof value !== "object" || value === null) throw new TypeError("invalid approval request");
  const record = value as Record<string, unknown>;
  if (
    record["v"] !== 1 ||
    record["type"] !== "approval.request" ||
    typeof record["operationId"] !== "string" ||
    record["operationId"].length === 0 ||
    record["operationId"].length > 128 ||
    typeof record["connectionId"] !== "string" ||
    record["connectionId"].length === 0 ||
    record["connectionId"].length > 128 ||
    typeof record["argumentHash"] !== "string" ||
    !/^[0-9a-f]{64}$/u.test(record["argumentHash"]) ||
    typeof record["operation"] !== "string" ||
    record["operation"].length === 0 ||
    record["operation"].length > 1_048_576 ||
    (record["cwd"] !== undefined && (typeof record["cwd"] !== "string" || !isAbsolute(record["cwd"]))) ||
    typeof record["timeoutMs"] !== "number" ||
    !Number.isFinite(record["timeoutMs"]) ||
    record["timeoutMs"] <= 0
  ) throw new TypeError("invalid approval request");
  const policyOperation = parsePolicyOperation(record["policyOperation"]);
  const display = policyOperation.kind === "bash" ? policyOperation.command : `${policyOperation.name} ${stableJson(policyOperation.arguments)}`;
  if (record["operation"] !== display || record["cwd"] !== policyOperation.cwd) throw new TypeError("invalid approval request");
  return { ...record, policyOperation } as unknown as WireRequest;
}

function parsePolicyOperation(value: unknown): PolicyOperation {
  if (typeof value !== "object" || value === null) throw new TypeError("invalid policy operation");
  const record = value as Record<string, unknown>;
  if (record["kind"] === "bash" && typeof record["command"] === "string" && record["command"].length > 0 && typeof record["cwd"] === "string" && isAbsolute(record["cwd"])) {
    return { kind: "bash", command: record["command"], cwd: record["cwd"] };
  }
  if ((record["kind"] === "tool" || record["kind"] === "bridge") && typeof record["name"] === "string" && record["name"].length > 0 && (record["cwd"] === undefined || typeof record["cwd"] === "string" && isAbsolute(record["cwd"]))) {
    stableJson(record["arguments"]);
    return { kind: record["kind"], name: record["name"], arguments: record["arguments"], ...(record["cwd"] === undefined ? {} : { cwd: record["cwd"] }) };
  }
  throw new TypeError("invalid policy operation");
}

function normalizeAllowed(operation: PolicyOperation): string {
  return operation.kind === "bash"
    ? `${operation.cwd}\n${operation.command.trim()}`
    : stableJson({ name: operation.name.trim().toLowerCase(), arguments: operation.arguments, cwd: operation.cwd });
}

function stableJson(value: unknown): string {
  const result = JSON.stringify(value) as string | undefined;
  if (result === undefined || Buffer.byteLength(result, "utf8") > 1_048_576) throw new TypeError("invalid policy operation");
  return result;
}

async function removeStaleSocket(path: string): Promise<void> {
  let metadata;
  try {
    metadata = await lstat(path);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return;
    throw error;
  }
  if (!metadata.isSocket() || metadata.uid !== process.getuid?.()) throw new Error("approval socket path is not an owned socket");
  const active = await new Promise<boolean>((resolvePromise) => {
    const probe = createConnection({ path });
    const timer = setTimeout(() => { probe.destroy(); resolvePromise(true); }, 250);
    probe.once("connect", () => { clearTimeout(timer); probe.destroy(); resolvePromise(true); });
    probe.once("error", () => { clearTimeout(timer); resolvePromise(false); });
  });
  if (active) throw new Error("approval socket server is already active");
  await unlink(path);
}
