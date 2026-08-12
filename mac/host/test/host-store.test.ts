import { chmod, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { HostStore } from "../src/daemon/host-store.js";

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function storePath(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-store-"));
  roots.push(root);
  return join(root, "host-store.json");
}

const device = {
  deviceId: "550e8400-e29b-41d4-a716-446655440001",
  certificateId: "a".repeat(64),
  deviceRouteKeyId: "route-key-1",
  createdAtMs: 1_700_000_000_000,
};

const endpoint = {
  deviceId: device.deviceId,
  endpointId: "550e8400-e29b-41d4-a716-446655440099",
  distributor: "ntfy",
  endpoint: "https://ntfy.example.com/topic",
  wakePublicKey: "d2FrZS1rZXk",
};

describe("HostStore", () => {
  it("creates a mode-0600 store with a fresh instance id", async () => {
    const path = await storePath();
    const store = new HostStore(path);
    await store.load(() => "550e8400-e29b-41d4-a716-446655440000");
    expect(store.instanceId()).toBe("550e8400-e29b-41d4-a716-446655440000");
    const metadata = await stat(path);
    expect(metadata.mode & 0o777).toBe(0o600);
  });

  it("persists devices across reloads and removes them", async () => {
    const path = await storePath();
    const first = new HostStore(path);
    await first.load(() => "550e8400-e29b-41d4-a716-446655440000");
    await first.addDevice(device);

    const second = new HostStore(path);
    await second.load(() => "550e8400-e29b-41d4-a716-4466554400ff");
    expect(second.findDevice(device.deviceId)).toMatchObject({ deviceId: device.deviceId });
    expect(second.instanceId()).toBe("550e8400-e29b-41d4-a716-446655440000");
    await expect(second.addDevice(device)).rejects.toMatchObject({ code: "SECURITY_CEREMONY_REPLAY" });
    expect(await second.removeDevice(device.deviceId)).toBe(true);
    expect(second.findDevice(device.deviceId)).toBeUndefined();
  });

  it("persists a keyless push endpoint across reloads", async () => {
    const path = await storePath();
    const first = new HostStore(path);
    await first.load(() => "550e8400-e29b-41d4-a716-446655440000");
    await first.addDevice(device);
    const keyless = {
      deviceId: endpoint.deviceId,
      endpointId: endpoint.endpointId,
      distributor: endpoint.distributor,
      endpoint: endpoint.endpoint,
    };
    await first.setPushEndpoint(keyless);

    const second = new HostStore(path);
    await second.load(() => "550e8400-e29b-41d4-a716-4466554400ff");
    expect(second.pushEndpoint()).toEqual(keyless);
  });

  it("clears a push endpoint only for the matching device and endpoint", async () => {
    const path = await storePath();
    const store = new HostStore(path);
    await store.load(() => "550e8400-e29b-41d4-a716-446655440000");
    await store.addDevice(device);
    await store.setPushEndpoint(endpoint);
    expect(store.pushEndpoint()).toMatchObject({ endpointId: endpoint.endpointId });
    await store.clearPushEndpoint(device.deviceId, "550e8400-e29b-41d4-a716-4466554400aa");
    expect(store.pushEndpoint()).toBeDefined();
    await store.clearPushEndpoint(device.deviceId, endpoint.endpointId);
    expect(store.pushEndpoint()).toBeUndefined();
  });

  it("fails closed on malformed JSON", async () => {
    const path = await storePath();
    await writeFile(path, "not json\n", { mode: 0o600 });
    const store = new HostStore(path);
    await expect(store.load(() => "550e8400-e29b-41d4-a716-446655440000")).rejects.toMatchObject({
      code: "SECURITY_KEY_STORAGE_FAILED",
    });
  });

  it("fails closed on loose file permissions", async () => {
    const path = await storePath();
    const store = new HostStore(path);
    await store.load(() => "550e8400-e29b-41d4-a716-446655440000");
    await chmod(path, 0o644);
    const reread = new HostStore(path);
    await expect(reread.load(() => "550e8400-e29b-41d4-a716-446655440000")).rejects.toMatchObject({
      code: "SECURITY_KEY_STORAGE_FAILED",
    });
  });

  it("rejects invalid device records", async () => {
    const path = await storePath();
    const store = new HostStore(path);
    await store.load(() => "550e8400-e29b-41d4-a716-446655440000");
    await expect(
      store.addDevice({ ...device, deviceId: "not-a-uuid" }),
    ).rejects.toMatchObject({ code: "SECURITY_INVALID_INPUT" });
    const raw = await readFile(path, "utf8");
    expect(raw).not.toContain("not-a-uuid");
  });
});
