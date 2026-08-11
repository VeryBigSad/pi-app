import { createHash, randomUUID } from "node:crypto";
import { createConnection } from "node:net";
import { isAbsolute } from "node:path";
import { classify, type PolicyOperation } from "@pimobile/approval";

export const POLICY_HOOK_KEY = "io.github.verybigsad.pimobile.policy.v1";
const MAX_WIRE_BYTES = 64 << 10;
const HOOK_TIMEOUT_MS = 150_000;

export interface BashPolicyInvocation {
  readonly version: 1;
  readonly kind: "bash";
  readonly operationId?: string;
  readonly command: string;
  readonly cwd: string;
  readonly signal: AbortSignal;
}

export interface ToolPolicyInvocation {
  readonly version: 1;
  readonly kind: "tool" | "bridge";
  readonly operationId?: string;
  readonly name: string;
  readonly arguments: unknown;
  readonly cwd?: string;
  readonly signal: AbortSignal;
}

export type PolicyInvocation = BashPolicyInvocation | ToolPolicyInvocation;

interface ApprovalWireRequest {
  readonly v: 1;
  readonly type: "approval.request";
  readonly operationId: string;
  readonly connectionId: string;
  readonly argumentHash: string;
  readonly operation: string;
  readonly policyOperation: PolicyOperation;
  readonly cwd?: string;
  readonly reasons: readonly string[];
  readonly timeoutMs: number;
}

interface ApprovalWireResult {
  readonly v: 1;
  readonly type: "approval.result";
  readonly operationId: string;
  readonly argumentHash: string;
  readonly allowed: boolean;
  readonly code?: string;
}

export function installPolicyHook(environment: NodeJS.ProcessEnv = process.env): void {
  const symbol = Symbol.for(POLICY_HOOK_KEY);
  const globals = globalThis as Record<symbol, unknown>;
  if (globals[symbol] !== undefined) throw new Error("Pi Mobile policy hook already installed");
  globals[symbol] = async (input: unknown): Promise<void> => {
    const invocation = parseInvocation(input);
    const policyOperation: PolicyOperation = invocation.kind === "bash"
      ? { kind: "bash", command: invocation.command, cwd: invocation.cwd }
      : { kind: invocation.kind, name: invocation.name, arguments: invocation.arguments, ...(invocation.cwd === undefined ? {} : { cwd: invocation.cwd }) };
    const classification = classify(policyOperation);
    if (classification.disposition === "allow") return;
    const operationId = invocation.operationId ?? randomUUID();
    const argumentHash = createHash("sha256").update(classification.normalized).digest("hex");
    const socketPath = environment["PI_MOBILE_APPROVAL_SOCKET"];
    if (socketPath === undefined || !isAbsolute(socketPath)) throw new Error("Pi Mobile approval broker unavailable");
    const request: ApprovalWireRequest = {
      v: 1,
      type: "approval.request",
      operationId,
      connectionId: environment["PI_MOBILE_CONNECTION_ID"] ?? `local-${String(process.pid)}`,
      argumentHash,
      operation: invocation.kind === "bash" ? invocation.command : `${invocation.name} ${stableJson(invocation.arguments)}`,
      policyOperation,
      ...(invocation.cwd === undefined ? {} : { cwd: invocation.cwd }),
      reasons: classification.reasons,
      timeoutMs: HOOK_TIMEOUT_MS,
    };
    const result = await requestApproval(socketPath, request, invocation.signal);
    if (!result.allowed) throw new Error(`Pi Mobile approval rejected: ${result.code ?? "APPROVAL_DENIED"}`);
  };
}

export function requestApproval(socketPath: string, request: ApprovalWireRequest, signal: AbortSignal): Promise<ApprovalWireResult> {
  return new Promise((resolvePromise, reject) => {
    let settled = false;
    let pending = Buffer.alloc(0);
    const socket = createConnection({ path: socketPath });
    const finish = (error?: Error, result?: ApprovalWireResult): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal.removeEventListener("abort", abort);
      socket.destroy();
      if (error !== undefined) reject(error);
      else if (result !== undefined) resolvePromise(result);
      else reject(new Error("Pi Mobile approval broker failed"));
    };
    const abort = (): void => finish(new Error("Pi Mobile approval canceled"));
    const timer = setTimeout(() => finish(new Error("Pi Mobile approval timed out")), HOOK_TIMEOUT_MS);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) {
      abort();
      return;
    }
    socket.once("connect", () => socket.write(`${JSON.stringify(request)}\n`));
    socket.on("data", (chunk: Buffer) => {
      pending = Buffer.concat([pending, chunk]);
      if (pending.length > MAX_WIRE_BYTES) {
        finish(new Error("Pi Mobile approval response exceeded its bound"));
        return;
      }
      const newline = pending.indexOf(0x0a);
      if (newline < 0) return;
      if (newline !== pending.length - 1) {
        finish(new Error("Pi Mobile approval response contained trailing data"));
        return;
      }
      try {
        const value = parseResult(JSON.parse(pending.subarray(0, newline).toString("utf8")), request);
        finish(undefined, value);
      } catch (error) {
        finish(error instanceof Error ? error : new Error("Pi Mobile approval response was invalid"));
      }
    });
    socket.once("error", () => finish(new Error("Pi Mobile approval broker unavailable")));
    socket.once("end", () => finish(new Error("Pi Mobile approval broker disconnected")));
  });
}

function parseInvocation(value: unknown): PolicyInvocation {
  if (typeof value !== "object" || value === null) throw new TypeError("Pi Mobile policy invocation is invalid");
  const record = value as Record<string, unknown>;
  const commonValid = record["version"] === 1 &&
    record["signal"] instanceof AbortSignal &&
    (record["operationId"] === undefined || typeof record["operationId"] === "string");
  const cwdValid = record["cwd"] === undefined || typeof record["cwd"] === "string" && isAbsolute(record["cwd"]);
  const bashValid = record["kind"] === "bash" && typeof record["command"] === "string" && record["command"].length > 0 && record["command"].length <= 1_048_576 && typeof record["cwd"] === "string";
  const toolValid = (record["kind"] === "tool" || record["kind"] === "bridge") && typeof record["name"] === "string" && record["name"].length > 0 && record["name"].length <= 128;
  if (!commonValid || !cwdValid || !bashValid && !toolValid) throw new TypeError("Pi Mobile policy invocation is invalid");
  if (toolValid) stableJson(record["arguments"]);
  return record as unknown as PolicyInvocation;
}

function stableJson(value: unknown): string {
  const result = JSON.stringify(value) as string | undefined;
  if (result === undefined || Buffer.byteLength(result, "utf8") > 1_048_576) throw new TypeError("Pi Mobile tool arguments are invalid");
  return result;
}

function parseResult(value: unknown, request: ApprovalWireRequest): ApprovalWireResult {
  if (typeof value !== "object" || value === null) throw new TypeError("Pi Mobile approval response was invalid");
  const record = value as Record<string, unknown>;
  if (
    record["v"] !== 1 ||
    record["type"] !== "approval.result" ||
    record["operationId"] !== request.operationId ||
    record["argumentHash"] !== request.argumentHash ||
    typeof record["allowed"] !== "boolean" ||
    (record["code"] !== undefined && typeof record["code"] !== "string")
  ) throw new TypeError("Pi Mobile approval response was invalid");
  return record as unknown as ApprovalWireResult;
}

installPolicyHook();
