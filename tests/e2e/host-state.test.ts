import { randomUUID } from "node:crypto";
import { access, chmod, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { inspectHostState, isolateHostState, restoreHostState } from "./host-state.js";

const roots: string[] = [];

async function fixture(): Promise<{
  readonly root: string;
  readonly dataDirectory: string;
  readonly backupParent: string;
  readonly storeBytes: Uint8Array;
  readonly routeBytes: Uint8Array;
}> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-host-state-test-"));
  roots.push(root);
  const dataDirectory = join(root, "data");
  const backupParent = join(root, "private-backup");
  await mkdir(join(dataDirectory, "relay"), { recursive: true, mode: 0o700 });
  const routeId = "route-test";
  const storeBytes = Buffer.from(`${JSON.stringify({
    version: 1,
    instanceId: randomUUID(),
    ownerUserId: "owner-user",
    ownerUserHandle: "owner-handle",
    ownerCredentials: [{ id: "secret-credential", publicKey: "secret-key", counter: 7 }],
    devices: [{ deviceId: randomUUID(), certificateId: "a".repeat(64), createdAtMs: 1 }],
    relay: { routeId },
  }, null, 2)}\n`, "utf8");
  const routeBytes = Buffer.from(`${JSON.stringify({ routeId, secret: "route-secret" }, null, 2)}\n`, "utf8");
  await writeFile(join(dataDirectory, "host-store.json"), storeBytes, { mode: 0o600 });
  await writeFile(join(dataDirectory, "relay", "route-state.json"), routeBytes, { mode: 0o600 });
  return { root, dataDirectory, backupParent, storeBytes, routeBytes };
}

afterEach(async () => {
  await Promise.all(roots.splice(0).map(async (root) => await rm(root, { recursive: true, force: true })));
});

describe("E2E host-state isolation", () => {
  it("clears only debug authentication ownership while preserving host and relay identity", async () => {
    const value = await fixture();
    const before = await inspectHostState(value.dataDirectory);
    const snapshot = await isolateHostState(value.dataDirectory, value.backupParent);
    const isolated = JSON.parse(await readFile(join(value.dataDirectory, "host-store.json"), "utf8")) as Record<string, unknown>;

    expect(await inspectHostState(value.dataDirectory)).toEqual({
      ...before,
      ownerCredentialCount: 0,
      deviceCount: 0,
    });
    expect(isolated).not.toHaveProperty("ownerUserId");
    expect(isolated).not.toHaveProperty("ownerUserHandle");
    expect(isolated["relay"]).toEqual({ routeId: before.routeId });
    await expect(readFile(join(snapshot.directory, "host-store.json"))).resolves.toEqual(value.storeBytes);
    await expect(readFile(join(snapshot.directory, "route-state.json"))).resolves.toEqual(value.routeBytes);
  });

  it("atomically restores byte-identical owner/device and route state", async () => {
    const value = await fixture();
    const snapshot = await isolateHostState(value.dataDirectory, value.backupParent);
    await writeFile(join(value.dataDirectory, "relay", "route-state.json"), "{}\n", { mode: 0o600 });

    await restoreHostState(value.dataDirectory, snapshot);

    await expect(readFile(join(value.dataDirectory, "host-store.json"))).resolves.toEqual(value.storeBytes);
    await expect(readFile(join(value.dataDirectory, "relay", "route-state.json"))).resolves.toEqual(value.routeBytes);
    await expect(access(snapshot.directory)).rejects.toThrow();
  });

  it("restores state after a failure that occurs after a recovery snapshot is registered", async () => {
    const value = await fixture();
    let snapshotDirectory: string | undefined;

    await expect(isolateHostState(value.dataDirectory, value.backupParent, (snapshot) => {
      snapshotDirectory = snapshot.directory;
      throw new Error("E2E_TEST_INJECTED_ISOLATION_FAILURE");
    })).rejects.toThrow("E2E_TEST_INJECTED_ISOLATION_FAILURE");

    expect(snapshotDirectory).toBeDefined();
    await expect(readFile(join(value.dataDirectory, "host-store.json"))).resolves.toEqual(value.storeBytes);
    await expect(readFile(join(value.dataDirectory, "relay", "route-state.json"))).resolves.toEqual(value.routeBytes);
    await expect(access(snapshotDirectory ?? "")).resolves.toBeUndefined();
  });

  it("fails closed on unsafe store permissions without creating a backup", async () => {
    const value = await fixture();
    await chmod(join(value.dataDirectory, "host-store.json"), 0o644);

    await expect(isolateHostState(value.dataDirectory, value.backupParent)).rejects.toThrow("E2E_HOST_STORE_UNSAFE");
    await expect(access(value.backupParent)).rejects.toThrow();
  });

  it("rejects a host/route registration mismatch before mutation", async () => {
    const value = await fixture();
    await writeFile(
      join(value.dataDirectory, "relay", "route-state.json"),
      `${JSON.stringify({ routeId: "different-route" })}\n`,
      { mode: 0o600 },
    );

    await expect(isolateHostState(value.dataDirectory, value.backupParent)).rejects.toThrow("E2E_ROUTE_STATE_MISMATCH");
    await expect(readFile(join(value.dataDirectory, "host-store.json"))).resolves.toEqual(value.storeBytes);
  });
});
