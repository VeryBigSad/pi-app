import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminServer, adminCall } from "../src/daemon/admin-server.js";
import { NtfyPushPublisher, parseNtfyConfig } from "../src/daemon/push.js";

const roots: string[] = [];
const servers: AdminServer[] = [];

afterEach(async () => {
  vi.unstubAllGlobals();
  await Promise.allSettled(servers.splice(0).map(async (server) => server.stop()));
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function socketPath(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-admin-"));
  roots.push(root);
  return join(root, "admin.sock");
}

describe("AdminServer", () => {
  it("round-trips a request and response", async () => {
    const path = await socketPath();
    const server = new AdminServer(path, (request) => Promise.resolve({ echo: request.method }));
    servers.push(server);
    await server.start();
    await expect(adminCall(path, { method: "status" })).resolves.toEqual({ echo: "status" });
  });

  it("maps handler error codes without leaking messages", async () => {
    const path = await socketPath();
    const server = new AdminServer(path, () => {
      throw new Error("DEVICE_NOT_FOUND");
    });
    servers.push(server);
    await server.start();
    await expect(adminCall(path, { method: "devices.revoke" })).rejects.toThrow(/DEVICE_NOT_FOUND/);
  });

  it("masks non-code error messages", async () => {
    const path = await socketPath();
    const server = new AdminServer(path, () => {
      throw new Error("sensitive internals with secret detail");
    });
    servers.push(server);
    await server.start();
    await expect(adminCall(path, { method: "status" })).rejects.toThrow(/ADMIN_REJECTED/);
  });

  it("rejects malformed requests", async () => {
    const path = await socketPath();
    const server = new AdminServer(path, () => Promise.resolve({}));
    servers.push(server);
    await server.start();
    const { createConnection } = await import("node:net");
    const response = await new Promise<string>((resolveResponse) => {
      const socket = createConnection(path);
      socket.once("connect", () => socket.write("not json\n"));
      socket.on("data", (chunk: Buffer) => {
        resolveResponse(chunk.toString("utf8"));
        socket.destroy();
      });
    });
    expect(JSON.parse(response)).toMatchObject({ ok: false, error: { code: "INVALID_REQUEST" } });
  });

  it("reports DAEMON_UNAVAILABLE when nothing listens", async () => {
    const path = await socketPath();
    await expect(adminCall(path, { method: "status" }, 500)).rejects.toThrow(/DAEMON_UNAVAILABLE/);
  });
});

describe("NtfyPushPublisher", () => {
  const notice = {
    settlementId: "550e8400-e29b-41d4-a716-446655440003",
    sessionId: "550e8400-e29b-41d4-a716-446655440001",
    streamEpoch: "550e8400-e29b-41d4-a716-446655440002",
    sequence: "7",
    settledAtMs: 1_700_000_000_000,
  };

  it("publishes a wake notification with only catch-up metadata", async () => {
    const calls: { url: string; body: string }[] = [];
    vi.stubGlobal("fetch", (input: unknown, init?: { body?: unknown }) => {
      calls.push({ url: String(input), body: String(init?.body) });
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const publisher = new NtfyPushPublisher({ url: "https://ntfy.example.com", topic: "pi-mobile" });
    await publisher.publishWake(notice);
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe("https://ntfy.example.com/pi-mobile");
    expect(calls[0]?.body).not.toContain("prompt");
    expect(JSON.parse(calls[0]?.body ?? "{}")).toMatchObject({ kind: "wake", sessionId: notice.sessionId });
    expect(publisher.status()).toMatchObject({ configured: true, published: 1, failed: 0 });
  });

  it("deduplicates repeat publications and counts failures", async () => {
    let ok = true;
    vi.stubGlobal("fetch", () => {
      const response = new Response("", { status: ok ? 200 : 500 });
      ok = false;
      return Promise.resolve(response);
    });
    const publisher = new NtfyPushPublisher({ url: "https://ntfy.example.com", topic: "pi-mobile" });
    await publisher.publishWake(notice);
    await publisher.publishWake(notice);
    await publisher.publishWake({ ...notice, sequence: "8" });
    expect(publisher.status()).toMatchObject({ published: 1, failed: 1 });
  });

  it("is a no-op when not configured", async () => {
    const publisher = new NtfyPushPublisher(undefined);
    await publisher.publishWake(notice);
    expect(publisher.status()).toEqual({ configured: false, published: 0, failed: 0 });
  });

  it("validates distributor configuration", () => {
    expect(() => parseNtfyConfig({ url: "http://insecure.example.com", topic: "x" })).toThrow();
    expect(() => parseNtfyConfig({ url: "https://ntfy.example.com" })).toThrow();
    expect(parseNtfyConfig(undefined)).toBeUndefined();
    expect(parseNtfyConfig({ url: "https://ntfy.example.com", topic: "t" })).toMatchObject({ topic: "t" });
  });
});
