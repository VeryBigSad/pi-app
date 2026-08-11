import { mkdtempSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { SqliteRevocationRegistry } from "../src/security/revocation-registry.js";

describe("durable security revocations", () => {
  it("persists device, passkey, and route revocation without allowing overwrite", () => {
    const path = join(mkdtempSync(join(tmpdir(), "pimobile-revocations-")), "security.sqlite");
    const registry = new SqliteRevocationRegistry(path);
    expect(statSync(path).mode & 0o777).toBe(0o600);

    expect(registry.revoke({
      kind: "device_certificate",
      id: "01ab",
      reason: "credential_compromise",
      revokedAtMs: 100,
    })).toMatchObject({ reason: "credential_compromise", revokedAtMs: 100 });
    registry.revoke({ kind: "passkey", id: "credential_id", reason: "owner_reset", revokedAtMs: 101 });
    registry.revoke({ kind: "route_key", id: "route-key-1", reason: "superseded", revokedAtMs: 102 });
    expect(registry.revoke({
      kind: "device_certificate",
      id: "01ab",
      reason: "user_requested",
      revokedAtMs: 200,
    })).toMatchObject({ reason: "credential_compromise", revokedAtMs: 100 });
    registry.close();

    const reopened = new SqliteRevocationRegistry(path);
    expect(reopened.isRevoked("device_certificate", "01ab")).toBe(true);
    expect(reopened.isRevoked("passkey", "credential_id")).toBe(true);
    expect(reopened.isRevoked("route_key", "route-key-1")).toBe(true);
    expect(() => reopened.assertNotRevoked("passkey", "credential_id")).toThrow(
      expect.objectContaining({ code: "SECURITY_REVOKED" }),
    );
    expect(reopened.isRevoked("passkey", "other")).toBe(false);
    reopened.close();
    expect(readFileSync(path).subarray(0, 15).toString()).toBe("SQLite format 3");
  });

  it("rejects unbounded or malformed registry identities", () => {
    const path = join(mkdtempSync(join(tmpdir(), "pimobile-revocations-")), "security.sqlite");
    const registry = new SqliteRevocationRegistry(path);
    expect(() => registry.revoke({
      kind: "passkey",
      id: "a".repeat(1025),
      reason: "user_requested",
      revokedAtMs: 1,
    })).toThrow(expect.objectContaining({ code: "SECURITY_INVALID_INPUT" }));
    registry.close();
  });
});
