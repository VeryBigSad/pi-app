import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PatchManifest } from "@pimobile/pi-patch";
import { AdminServer, adminCall } from "../src/daemon/admin-server.js";
import { HostDaemon } from "../src/daemon/daemon.js";
import { NtfyPushPublisher, parseNtfyConfig } from "../src/daemon/push.js";
import type { PiRuntimeProvisioner } from "../src/runtime/supervisor.js";
import type { WrappingSecretProvider } from "../src/security/keychain.js";

const roots: string[] = [];
const servers: AdminServer[] = [];
const daemons: HostDaemon[] = [];

afterEach(async () => {
  vi.unstubAllGlobals();
  await Promise.allSettled(daemons.splice(0).map(async (daemon) => daemon.stop()));
  await Promise.allSettled(servers.splice(0).map(async (server) => server.stop()));
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function socketPath(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-admin-"));
  roots.push(root);
  return join(root, "admin.sock");
}

async function opaqueWakeFixture(): Promise<{ hostPayload: string; minBytes: number; maxBytes: number }> {
  const path = resolve(dirname(fileURLToPath(import.meta.url)), "../../../protocol/fixtures/opaque-wake-v1.json");
  const parsed: unknown = JSON.parse(await readFile(path, "utf8"));
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new TypeError("invalid wake fixture");
  const record = parsed as Record<string, unknown>;
  const hostPayload = record["hostPayload"];
  const minBytes = record["minBytes"];
  const maxBytes = record["maxBytes"];
  if (typeof hostPayload !== "string" || typeof minBytes !== "number" || typeof maxBytes !== "number") {
    throw new TypeError("invalid wake fixture");
  }
  return { hostPayload, minBytes, maxBytes };
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
  const registration = {
    deviceId: "550e8400-e29b-41d4-a716-446655440010",
    endpointId: "550e8400-e29b-41d4-a716-446655440011",
    distributor: "ntfy",
    endpoint: "https://ntfy.example.com/upAbCdEf123",
    wakePublicKey: "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8QcYP7DkM",
  };

  it("publishes to a stored keyless endpoint", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", (input: unknown) => {
      calls.push(String(input));
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const keyless = {
      deviceId: registration.deviceId,
      endpointId: registration.endpointId,
      distributor: registration.distributor,
      endpoint: registration.endpoint,
    };
    const publisher = new NtfyPushPublisher(
      { url: "https://ntfy.example.com" },
      () => keyless,
    );

    await publisher.publishWake(notice);

    expect(calls).toEqual([keyless.endpoint]);
    expect(publisher.status()).toMatchObject({ published: 1, failed: 0, skipped: 0 });
  });

  it("posts the shared Android-contract wake fixture without metadata", async () => {
    const fixture = await opaqueWakeFixture();
    const calls: { url: string; body: string; contentType: string | undefined }[] = [];
    vi.stubGlobal("fetch", (input: unknown, init?: { body?: unknown; headers?: Record<string, string> }) => {
      calls.push({
        url: String(input),
        body: String(init?.body),
        contentType: init?.headers?.["Content-Type"],
      });
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const publisher = new NtfyPushPublisher(
      { url: "https://ntfy.example.com" },
      () => registration,
      () => fixture.hostPayload,
    );
    await publisher.publishWake(notice);
    expect(calls).toEqual([{
      url: "https://ntfy.example.com/upAbCdEf123",
      body: fixture.hostPayload,
      contentType: "application/octet-stream",
    }]);
    expect(calls[0]?.body).toMatch(/^[A-Za-z0-9_-]+$/u);
    expect(calls[0]?.body.length).toBeGreaterThanOrEqual(fixture.minBytes);
    expect(calls[0]?.body.length).toBeLessThanOrEqual(fixture.maxBytes);
    expect(calls[0]?.body).not.toContain(notice.sessionId);
    expect(calls[0]?.body).not.toContain(String(notice.settledAtMs));
    expect(publisher.status()).toMatchObject({ configured: true, published: 1, failed: 0, skipped: 0 });
  });

  it("generates a fresh 32-byte opaque wake ID for each settlement", async () => {
    const bodies: string[] = [];
    vi.stubGlobal("fetch", (_input: unknown, init?: { body?: unknown }) => {
      bodies.push(String(init?.body));
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const publisher = new NtfyPushPublisher({ url: "https://ntfy.example.com" }, () => registration);
    await publisher.publishWake(notice);
    await publisher.publishWake({ ...notice, sequence: "8" });
    expect(bodies).toHaveLength(2);
    expect(new Set(bodies).size).toBe(2);
    for (const body of bodies) expect(body).toMatch(/^[A-Za-z0-9_-]{43}$/u);
  });

  it("fails closed before network I/O if an invalid wake ID is produced", async () => {
    const calls: unknown[] = [];
    vi.stubGlobal("fetch", (input: unknown) => {
      calls.push(input);
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const publisher = new NtfyPushPublisher(
      { url: "https://ntfy.example.com" },
      () => registration,
      () => JSON.stringify({ sessionId: notice.sessionId }),
    );
    await publisher.publishWake(notice);
    expect(calls).toHaveLength(0);
    expect(publisher.status()).toEqual({ configured: true, published: 0, failed: 1, skipped: 0 });
  });

  it("sends the bearer token header when configured", async () => {
    const auths: unknown[] = [];
    vi.stubGlobal("fetch", (_input: unknown, init?: { headers?: Record<string, string> }) => {
      auths.push(init?.headers?.["Authorization"]);
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const publisher = new NtfyPushPublisher(
      { url: "https://ntfy.example.com", token: "tk_secret" },
      () => registration,
    );
    await publisher.publishWake(notice);
    expect(auths).toEqual(["Bearer tk_secret"]);
  });

  it("rejects endpoints on foreign hosts (SSRF allowlist)", async () => {
    const calls: unknown[] = [];
    vi.stubGlobal("fetch", (input: unknown) => {
      calls.push(input);
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    for (const endpoint of [
      "https://evil.example.com/upXyz",
      "https://ntfy.example.com.evil.example/upXyz",
      "https://ntfy.example.com:4443/upXyz",
      "http://ntfy.example.com/upXyz",
      "https://attacker@ntfy.example.com/upXyz",
      "not a url",
    ]) {
      const publisher = new NtfyPushPublisher(
        { url: "https://ntfy.example.com" },
        () => ({ ...registration, endpoint }),
      );
      await publisher.publishWake(notice);
      expect(publisher.status()).toMatchObject({ published: 0, failed: 0, skipped: 1 });
    }
    expect(calls).toHaveLength(0);
  });

  it("is a skipped no-op when no endpoint is registered", async () => {
    const calls: unknown[] = [];
    vi.stubGlobal("fetch", (input: unknown) => {
      calls.push(input);
      return Promise.resolve(new Response("ok", { status: 200 }));
    });
    const publisher = new NtfyPushPublisher({ url: "https://ntfy.example.com" }, () => undefined);
    await publisher.publishWake(notice);
    expect(calls).toHaveLength(0);
    expect(publisher.status()).toEqual({ configured: true, published: 0, failed: 0, skipped: 1 });
  });

  it("deduplicates repeat publications and counts failures", async () => {
    let ok = true;
    vi.stubGlobal("fetch", () => {
      const response = new Response("", { status: ok ? 200 : 500 });
      ok = false;
      return Promise.resolve(response);
    });
    const publisher = new NtfyPushPublisher({ url: "https://ntfy.example.com" }, () => registration);
    await publisher.publishWake(notice);
    await publisher.publishWake(notice);
    await publisher.publishWake({ ...notice, sequence: "8" });
    expect(publisher.status()).toMatchObject({ published: 1, failed: 1 });
  });

  it("is a no-op when not configured", async () => {
    const publisher = new NtfyPushPublisher(undefined);
    await publisher.publishWake(notice);
    expect(publisher.status()).toEqual({ configured: false, published: 0, failed: 0, skipped: 0 });
  });

  it("validates distributor configuration", () => {
    expect(() => parseNtfyConfig({ url: "http://insecure.example.com" })).toThrow();
    expect(() => parseNtfyConfig({ token: "tk_x" })).toThrow();
    expect(() => parseNtfyConfig({ url: "https://ntfy.example.com", token: 42 })).toThrow();
    expect(parseNtfyConfig(undefined)).toBeUndefined();
    expect(parseNtfyConfig({ url: "https://ntfy.example.com" })).toEqual({ url: "https://ntfy.example.com" });
    expect(parseNtfyConfig({ url: "https://ntfy.example.com", token: "tk_x" })).toEqual({
      url: "https://ntfy.example.com",
      token: "tk_x",
    });
    expect(parseNtfyConfig({ url: "https://ntfy.example.com", topic: "legacy" })).toEqual({
      url: "https://ntfy.example.com",
    });
  });
});

describe("HostDaemon ntfy config", () => {
  const manifest: PatchManifest = {
    piVersion: "0.84.0",
    originalAgentSessionSha256: "a".repeat(64),
    patchedAgentSessionSha256: "b".repeat(64),
    policyHookKey: "io.github.verybigsad.pimobile.policy.v1",
  };
  const secrets: WrappingSecretProvider = {
    getSecret: () => Promise.resolve(Buffer.alloc(32, 7)),
  };
  const preloadPath = resolve(dirname(fileURLToPath(import.meta.url)), "../../preload/dist/index.js");

  async function daemonWithConfig(config: Record<string, unknown>): Promise<HostDaemon> {
    const root = await mkdtemp(join(tmpdir(), "pi-mobile-daemon-"));
    roots.push(root);
    const target = join(root, "pi");
    let valid = false;
    const provisioner: PiRuntimeProvisioner = {
      targetRoot: () => target,
      sourceRoot: () => Promise.resolve(join(root, "source")),
      install: async (_source, targetRoot) => {
        await mkdir(join(targetRoot, "dist"), { recursive: true });
        await writeFile(
          join(targetRoot, "dist", "cli.js"),
          'let input = "";\n'
            + 'process.stdin.on("data", chunk => {\n'
            + '  input += chunk.toString("utf8");\n'
            + '  for (;;) {\n'
            + '    const newline = input.indexOf("\\n");\n'
            + '    if (newline < 0) return;\n'
            + '    const line = input.slice(0, newline);\n'
            + '    input = input.slice(newline + 1);\n'
            + '    const command = JSON.parse(line);\n'
            + '    process.stdout.write(JSON.stringify({type:"response", id:command.id, command:command.type, success:true, data:{ok:true}}) + "\\n");\n'
            + '  }\n'
            + '});\n',
          { mode: 0o700 },
        );
        valid = true;
        return manifest;
      },
      verify: () => (valid ? Promise.resolve(manifest) : Promise.reject(new Error("not installed"))),
    };
    await writeFile(join(root, "config.json"), `${JSON.stringify(config)}\n`, { mode: 0o600 });
    const daemon = new HostDaemon(root, { provisioner, preloadPath, secrets });
    daemons.push(daemon);
    return daemon;
  }

  it("parses the ntfy section from config.json", async () => {
    const daemon = await daemonWithConfig({
      ports: { direct: 0, provisional: 0 },
      ntfy: { url: "https://ntfy.example.com", token: "tk_x" },
    });
    await daemon.start();
    const status = (await adminCall(daemon.paths().adminSocketPath, { method: "status" })) as {
      push: { configured: boolean; published: number; failed: number; skipped: number };
    };
    expect(status.push).toEqual({ configured: true, published: 0, failed: 0, skipped: 0 });
  });

  it("rejects an insecure ntfy url at startup", async () => {
    const daemon = await daemonWithConfig({
      ports: { direct: 0, provisional: 0 },
      ntfy: { url: "http://insecure.example.com" },
    });
    await expect(daemon.start()).rejects.toThrow();
  });
});
