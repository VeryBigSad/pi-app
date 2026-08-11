import { generateKeyPairSync } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  PAIRING_INVITATION_URI_PREFIX,
  decodePairingInvitationUri,
  encodePairingInvitationUri,
  signPairingInvitation,
  validateInvitationPayload,
  verifyPairingInvitation,
  type PairingInvitationPayload,
} from "../src/security/pairing-invitation.js";
import { NodeP256RouteSigner } from "../src/relay/proof.js";
import { canonicalizeJson, type JsonObject } from "@pimobile/protocol";

function routeSigner(keyId: string): NodeP256RouteSigner {
  return new NodeP256RouteSigner(keyId, generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey);
}

function payload(overrides: Partial<PairingInvitationPayload> = {}): PairingInvitationPayload {
  return {
    version: 1,
    relayUrl: "wss://relay.example",
    routeId: "route-1",
    routeKeyId: "mac-1",
    invitationId: "550e8400-e29b-41d4-a716-446655440040",
    expiresAt: "2026-08-09T12:05:00.000Z",
    nonce: Buffer.alloc(32, 3).toString("base64url"),
    serverCertificateSha256: "ab".repeat(32),
    directCandidates: [{ host: "192.168.1.10", port: 4412 }],
    macInstanceId: "550e8400-e29b-41d4-a716-446655440041",
    ...overrides,
  };
}

describe("signed pairing invitation envelope", () => {
  it("produces a JCS-canonical P-256 route-key signature that verifies against the route key", async () => {
    const signer = routeSigner("mac-1");
    const signed = await signPairingInvitation(payload(), signer);
    expect(signed.signature).toMatch(/^[A-Za-z0-9_-]+$/);
    const der = Buffer.from(signed.signature, "base64url");
    expect(der[0]).toBe(0x30);
    expect(der.length).toBeLessThanOrEqual(72);

    const spki = await signer.publicKeySpki();
    expect(verifyPairingInvitation(signed, spki)).toBe(true);
    const wrongKey = routeSigner("mac-2");
    expect(verifyPairingInvitation(signed, await wrongKey.publicKeySpki())).toBe(false);

    const tampered = { ...signed, signed: { ...signed.signed, nonce: Buffer.alloc(32, 4).toString("base64url") } };
    expect(verifyPairingInvitation(tampered, spki)).toBe(false);
  });

  it("signs exactly the JCS canonical form of the signed payload", async () => {
    const signer = routeSigner("mac-1");
    const signed = await signPairingInvitation(payload(), signer);
    const canonical = canonicalizeJson(signed.signed as unknown as JsonObject);
    const keys = Object.keys(JSON.parse(canonical) as Record<string, unknown>);
    expect(keys).toEqual([...keys].sort());
    expect(canonical).toContain("\"directCandidates\":[{");
  });

  it("round-trips through the pimobile://pair?v=1&d= URI envelope", async () => {
    const signer = routeSigner("mac-1");
    const signed = await signPairingInvitation(payload({
      relayPairing: { pairingId: "pair-1", secret: Buffer.alloc(32, 5).toString("base64url"), expiresAt: "2026-08-09T12:05:00.000Z" },
    }), signer);
    const uri = encodePairingInvitationUri(signed);
    expect(uri.startsWith(PAIRING_INVITATION_URI_PREFIX)).toBe(true);
    const decoded = decodePairingInvitationUri(uri);
    expect(decoded).toEqual(signed);
    expect(verifyPairingInvitation(decoded, await signer.publicKeySpki())).toBe(true);
  });

  it("rejects signers that do not match routeKeyId and malformed fields", async () => {
    await expect(signPairingInvitation(payload(), routeSigner("other-key"))).rejects.toThrow(/routeKeyId/);
    expect(() => validateInvitationPayload(payload({ relayUrl: "https://relay.example" }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ invitationId: "not-a-uuid" }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ macInstanceId: "550E8400-E29B-41D4-A716-446655440041" }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ serverCertificateSha256: "AB".repeat(32) }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ nonce: "too-short" }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ expiresAt: "not-a-date" }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ directCandidates: [{ host: "", port: 0 }] }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({ directCandidates: Array.from({ length: 17 }, () => ({ host: "10.0.0.1", port: 1 })) }))).toThrow(/invalid/);
    expect(() => validateInvitationPayload(payload({
      relayPairing: { pairingId: "bad/id", secret: "x", expiresAt: "2026-08-09T12:05:00.000Z" },
    }))).toThrow(/invalid/);
  });

  it("rejects malformed URIs and payloads on decode", () => {
    expect(() => decodePairingInvitationUri("pimobile://pair?v=2&d=aaaa")).toThrow(/invalid/);
    expect(() => decodePairingInvitationUri(`${PAIRING_INVITATION_URI_PREFIX}not-json`)).toThrow(/invalid/);
    const unsigned = Buffer.from(JSON.stringify({ signed: payload() }), "utf8").toString("base64url");
    expect(() => decodePairingInvitationUri(`${PAIRING_INVITATION_URI_PREFIX}${unsigned}`)).toThrow(/invalid/);
  });
});
