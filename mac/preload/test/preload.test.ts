import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { ApprovalSocketServer, type ApprovalOffer } from "@pimobile/approval";
import { POLICY_HOOK_KEY, installPolicyHook, type BashPolicyInvocation, type ToolPolicyInvocation } from "../src/index.js";

const symbol = Symbol.for(POLICY_HOOK_KEY);
const globals = globalThis as Record<symbol, unknown>;
const servers: ApprovalSocketServer[] = [];

afterEach(async () => {
  Reflect.deleteProperty(globals, symbol);
  await Promise.all(servers.splice(0).map((server) => server.close()));
});

describe("Pi policy preload", () => {
  it("allows classified reads without opening a broker socket", async () => {
    Reflect.deleteProperty(globals, symbol);
    installPolicyHook({});
    await expect(hook()({
      version: 1,
      kind: "bash",
      command: "git status",
      cwd: "/tmp",
      signal: new AbortController().signal,
    })).resolves.toBeUndefined();
  });

  it("binds a destructive decision to exact final arguments and origin", async () => {
    Reflect.deleteProperty(globals, symbol);
    const socketPath = join(mkdtempSync(join(tmpdir(), "pi-mobile-approval-")), "broker.sock");
    let deliverOffer: ((offer: ApprovalOffer) => void) | undefined;
    const offered = new Promise<ApprovalOffer>((resolve) => { deliverOffer = resolve; });
    const server = new ApprovalSocketServer({ socketPath, onOffer: (offer) => deliverOffer?.(offer) });
    servers.push(server);
    await server.start();
    installPolicyHook({ PI_MOBILE_APPROVAL_SOCKET: socketPath, PI_MOBILE_CONNECTION_ID: "rpc-connection" });
    const invocation: BashPolicyInvocation = {
      version: 1,
      kind: "bash",
      operationId: "operation-1",
      command: "prefix=true\nrm -rf target",
      cwd: "/tmp/project",
      signal: new AbortController().signal,
    };
    const result = hook()(invocation);
    const offer = await offered;
    expect(offer.operation).toBe(invocation.command);
    expect(offer.connectionId).toBe("rpc-connection");
    expect(server.decide({
      offerId: offer.offerId,
      operationId: offer.operationId,
      argumentHash: offer.argumentHash,
      connectionId: offer.connectionId,
      decision: "allow_once",
    })).toBe(true);
    await expect(result).resolves.toBeUndefined();
  });

  it("reclassifies exact final tool arguments in the broker", async () => {
    Reflect.deleteProperty(globals, symbol);
    const socketPath = join(mkdtempSync(join(tmpdir(), "pi-mobile-tool-")), "broker.sock");
    let deliverOffer: ((offer: ApprovalOffer) => void) | undefined;
    const offered = new Promise<ApprovalOffer>((resolve) => { deliverOffer = resolve; });
    const server = new ApprovalSocketServer({ socketPath, onOffer: (offer) => deliverOffer?.(offer) });
    servers.push(server);
    await server.start();
    installPolicyHook({ PI_MOBILE_APPROVAL_SOCKET: socketPath, PI_MOBILE_CONNECTION_ID: "tool-origin" });
    const result = hook()({
      version: 1,
      kind: "tool",
      operationId: "tool-1",
      name: "write",
      arguments: { path: "/tmp/file", content: "value" },
      cwd: "/tmp",
      signal: new AbortController().signal,
    });
    const offer = await offered;
    expect(offer).toMatchObject({ operationId: "tool-1", connectionId: "tool-origin", operation: "write {\"path\":\"/tmp/file\",\"content\":\"value\"}" });
    server.decide({ offerId: offer.offerId, operationId: offer.operationId, argumentHash: offer.argumentHash, connectionId: offer.connectionId, decision: "deny" });
    await expect(result).rejects.toThrow("APPROVAL_DENIED");
  });

  it("fails closed when approval transport is absent", async () => {
    Reflect.deleteProperty(globals, symbol);
    installPolicyHook({});
    await expect(hook()({
      version: 1,
      kind: "bash",
      command: "rm -rf target",
      cwd: "/tmp",
      signal: new AbortController().signal,
    })).rejects.toThrow("broker unavailable");
  });
});

function hook(): (invocation: BashPolicyInvocation | ToolPolicyInvocation) => Promise<void> {
  const value = globals[symbol];
  if (typeof value !== "function") throw new Error("hook unavailable");
  return value as (invocation: BashPolicyInvocation | ToolPolicyInvocation) => Promise<void>;
}
