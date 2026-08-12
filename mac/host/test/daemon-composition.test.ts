import { access, mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import type { PatchManifest } from "@pimobile/pi-patch";
import { HostDaemon } from "../src/daemon/daemon.js";
import { adminCall } from "../src/daemon/admin-server.js";
import type { PiRuntimeProvisioner } from "../src/runtime/supervisor.js";
import type { WrappingSecretProvider } from "../src/security/keychain.js";

const manifest: PatchManifest = {
  piVersion: "0.84.0",
  originalAgentSessionSha256: "a".repeat(64),
  patchedAgentSessionSha256: "b".repeat(64),
  policyHookKey: "io.github.verybigsad.pimobile.policy.v1",
};

const fakePi = `
let input = "";
process.stdin.on("data", chunk => {
  input += chunk.toString("utf8");
  for (;;) {
    const newline = input.indexOf("\\n");
    if (newline < 0) return;
    const line = input.slice(0, newline);
    input = input.slice(newline + 1);
    const command = JSON.parse(line);
    process.stdout.write(JSON.stringify({type:"response", id:command.id, command:command.type, success:true, data:{ok:true}}) + "\\n");
  }
});
`;

const secrets: WrappingSecretProvider = {
  getSecret: () => Promise.resolve(Buffer.alloc(32, 7)),
};

const preloadPath = resolve(dirname(fileURLToPath(import.meta.url)), "../../preload/dist/index.js");
const roots: string[] = [];
const daemons: HostDaemon[] = [];

afterEach(async () => {
  await Promise.allSettled(daemons.splice(0).map(async (daemon) => daemon.stop()));
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function fixture(): Promise<{ root: string; daemon: HostDaemon }> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-daemon-"));
  roots.push(root);
  const target = join(root, "pi");
  let valid = false;
  const provisioner: PiRuntimeProvisioner = {
    targetRoot: () => target,
    sourceRoot: () => Promise.resolve(join(root, "source")),
    install: async (_source, targetRoot) => {
      await mkdir(join(targetRoot, "dist"), { recursive: true });
      await writeFile(join(targetRoot, "dist", "cli.js"), fakePi, { mode: 0o700 });
      valid = true;
      return manifest;
    },
    verify: () => (valid ? Promise.resolve(manifest) : Promise.reject(new Error("not installed"))),
  };
  await writeFile(
    join(root, "config.json"),
    `${JSON.stringify({ ports: { direct: 0, provisional: 0 } })}\n`,
    { mode: 0o600 },
  );
  const daemon = new HostDaemon(root, { provisioner, preloadPath, secrets });
  daemons.push(daemon);
  return { root, daemon };
}

describe("HostDaemon composition", () => {
  it("starts the full stack and answers admin status", async () => {
    const { daemon } = await fixture();
    await daemon.start();
    const status = (await adminCall(daemon.paths().adminSocketPath, { method: "status" })) as {
      ok: boolean;
      piVersion: string;
      listeners: { directPort: number; provisionalPort: number };
      relay: { state: string };
    };
    expect(status.ok).toBe(true);
    expect(status.piVersion).toBe("0.84.0");
    expect(status.listeners.directPort).toBeGreaterThan(0);
    expect(status.listeners.provisionalPort).toBeGreaterThan(0);
    expect(status.listeners.directPort).not.toBe(status.listeners.provisionalPort);
    expect(status.relay.state).toBe("disabled");
  });

  it("runs a scripted command through session dispatch and the supervised Pi child", async () => {
    const { daemon } = await fixture();
    await daemon.start();
    const sessionId = randomUUID();
    const result = (await adminCall(daemon.paths().adminSocketPath, {
      method: "sessions.run",
      params: { sessionId, operation: "get_state" },
    })) as { type: string; command: string; success: boolean };
    expect(result).toMatchObject({ type: "response", command: "get_state", success: true });
    expect(daemon.status().sessions).toContain(sessionId);
  });

  it("creates and deletes only a capability-owned E2E session through admin", async () => {
    const { root, daemon } = await fixture();
    await daemon.start();
    const existingSessionId = randomUUID();
    await adminCall(daemon.paths().adminSocketPath, {
      method: "sessions.run",
      params: { sessionId: existingSessionId, operation: "get_state" },
    });
    const created = (await adminCall(daemon.paths().adminSocketPath, { method: "sessions.e2e.create" })) as {
      sessionId: string;
      deleteToken: string;
    };
    expect(created.sessionId).toMatch(/^[0-9a-f-]{36}$/);
    expect(created.deleteToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(daemon.status().sessions).toEqual(expect.arrayContaining([existingSessionId, created.sessionId]));
    await expect(adminCall(daemon.paths().adminSocketPath, {
      method: "sessions.e2e.delete",
      params: { sessionId: existingSessionId, deleteToken: created.deleteToken },
    })).rejects.toThrow("E2E_SESSION_OWNERSHIP_REQUIRED");
    await adminCall(daemon.paths().adminSocketPath, { method: "sessions.e2e.delete", params: created });
    expect(daemon.status().sessions).toEqual([existingSessionId]);
    await expect(access(join(root, "sessions", created.sessionId))).rejects.toThrow();
    await adminCall(daemon.paths().adminSocketPath, { method: "sessions.e2e.delete", params: created });
    expect(daemon.status().sessions).toEqual([existingSessionId]);
  });

  it("rejects operations outside the allow-list", async () => {
    const { daemon } = await fixture();
    await daemon.start();
    await expect(
      adminCall(daemon.paths().adminSocketPath, {
        method: "sessions.run",
        params: { sessionId: randomUUID(), operation: "exec_arbitrary" },
      }),
    ).rejects.toThrow(/COMMAND_REJECTED/);
  });

  it("fails closed when a second daemon binds the same data directory", async () => {
    const { root, daemon } = await fixture();
    await daemon.start();
    const second = new HostDaemon(root, { secrets });
    daemons.push(second);
    await expect(second.start()).rejects.toThrow(/DAEMON_ALREADY_RUNNING/);
  });

  it("restarts cleanly reusing persisted key material", async () => {
    const { root, daemon } = await fixture();
    await daemon.start();
    const firstStatus = daemon.status();
    await daemon.stop();
    daemons.splice(daemons.indexOf(daemon), 1);

    const target = join(root, "pi");
    const provisioner: PiRuntimeProvisioner = {
      targetRoot: () => target,
      sourceRoot: () => Promise.resolve(join(root, "source")),
      install: () => Promise.reject(new Error("install must not run on restart")),
      verify: () => Promise.resolve(manifest),
    };
    const restarted = new HostDaemon(root, { provisioner, preloadPath, secrets });
    daemons.push(restarted);
    await restarted.start();
    const secondStatus = restarted.status();
    expect(secondStatus.ok).toBe(true);
    expect(secondStatus.piVersion).toBe(firstStatus.piVersion);
  });

  it("reports unknown admin methods without leaking internals", async () => {
    const { daemon } = await fixture();
    await daemon.start();
    await expect(
      adminCall(daemon.paths().adminSocketPath, { method: "no.such.method" }),
    ).rejects.toThrow(/UNKNOWN_METHOD/);
  });
});
