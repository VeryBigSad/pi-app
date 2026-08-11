import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { loadOrCreateTlsMaterial } from "../src/daemon/tls-material.js";
import type { WrappingSecretProvider } from "../src/security/keychain.js";

const secrets: WrappingSecretProvider = {
  getSecret: () => Promise.resolve(Buffer.alloc(32, 13)),
};

const roots: string[] = [];

afterEach(async () => {
  await Promise.allSettled(roots.splice(0).map(async (root) => rm(root, { recursive: true, force: true })));
});

async function keyDirectory(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "pi-mobile-material-"));
  roots.push(root);
  return join(root, "keys");
}

const instanceId = "550e8400-e29b-41d4-a716-446655440000";

describe("loadOrCreateTlsMaterial", () => {
  it("creates a CA and server leaf, then reuses them on reload", async () => {
    const directory = await keyDirectory();
    const first = await loadOrCreateTlsMaterial({ keyDirectory: directory, secrets, instanceId });
    expect(first.caCertificatePem).toContain("BEGIN CERTIFICATE");
    expect(first.serverCertificatePem).toContain("BEGIN CERTIFICATE");
    expect(first.serverCertificateSha256).toMatch(/^[0-9a-f]{64}$/);
    expect(first.serverNotAfterMs).toBeGreaterThan(Date.now());

    const second = await loadOrCreateTlsMaterial({ keyDirectory: directory, secrets, instanceId });
    expect(second.caCertificatePem).toBe(first.caCertificatePem);
    expect(second.serverCertificateSha256).toBe(first.serverCertificateSha256);
  });

  it("isolates key directories from each other", async () => {
    const firstDirectory = await keyDirectory();
    const secondDirectory = await keyDirectory();
    const first = await loadOrCreateTlsMaterial({ keyDirectory: firstDirectory, secrets, instanceId });
    const second = await loadOrCreateTlsMaterial({ keyDirectory: secondDirectory, secrets, instanceId });
    expect(second.caCertificatePem).not.toBe(first.caCertificatePem);
  });
});
