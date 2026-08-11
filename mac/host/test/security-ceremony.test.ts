import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  PAIRING_INVITATION_TTL_MS,
  PairingCeremonyStore,
  type CeremonyBinding,
} from "../src/security/pairing-ceremony.js";

const invitationId = "550e8400-e29b-41d4-a716-446655440000";
const ceremonyId = "550e8400-e29b-41d4-a716-446655440001";
const secondInvitationId = "550e8400-e29b-41d4-a716-446655440002";
const csrHash = new Uint8Array(32).fill(4);

function store(ids = [invitationId, ceremonyId]): PairingCeremonyStore {
  let randomValue = 10;
  let idIndex = 0;
  return new PairingCeremonyStore(
    (size) => new Uint8Array(size).fill(randomValue++),
    () => ids[idIndex++] ?? secondInvitationId,
  );
}

/** Deterministic pairing token: the second random draw inside begin(). */
function pairingToken(): Uint8Array {
  return new Uint8Array(32).fill(11);
}

function binding(
  kind: CeremonyBinding["kind"],
  invitation = invitationId,
  token = pairingToken(),
): CeremonyBinding {
  return { kind, invitationId: invitation, pairingToken: token, csrSha256: csrHash };
}

describe("pairing ceremony bindings", () => {
  it("creates a five-minute first-owner registration bound to an in-channel pairing token, CSR, invitation, and kind", () => {
    const ceremonies = store();
    const invitation = ceremonies.issueInvitation(1_000);
    const ceremony = ceremonies.begin({
      invitationId: invitation.invitationId,
      csrSha256: csrHash,
      ownerCredentialId: null,
      nowMs: 2_000,
    });

    const expectedToken = pairingToken();
    expect(invitation.expiresAtMs - invitation.createdAtMs).toBe(PAIRING_INVITATION_TTL_MS);
    expect(ceremony).toMatchObject({
      kind: "first-owner-registration",
      invitationId,
      status: "challenge_pending",
    });
    expect(ceremony.pairingToken).toBe(Buffer.from(expectedToken).toString("base64url"));
    expect(ceremony.sessionBinding).toBe(createHash("sha256").update(expectedToken).digest("hex"));
    expect(ceremony.challenge).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(ceremony.shortCode).toMatch(/^\d{6}$/);
  });

  it("uses a distinct later-device assertion state and consumes challenge and invitation once", () => {
    const ceremonies = store();
    ceremonies.issueInvitation(1_000);
    const ceremony = ceremonies.begin({
      invitationId,
      csrSha256: csrHash,
      ownerCredentialId: "owner_credential",
      nowMs: 1_100,
    });
    const expectedBinding = binding("later-device-assertion");

    expect(ceremony).toMatchObject({
      kind: "later-device-assertion",
      ownerCredentialId: "owner_credential",
    });
    expect(ceremonies.takeForWebAuthnVerification(ceremony.challenge, expectedBinding, 1_200).status)
      .toBe("challenge_consumed");
    expect(ceremonies.recordWebAuthnResult(ceremony.ceremonyId, true).status).toBe("webauthn_verified");
    expect(ceremonies.confirmLocally(ceremony.ceremonyId, true, 1_300).status).toBe("local_confirmed");
    expect(ceremonies.consumeForCertificateIssuance(ceremony.ceremonyId, expectedBinding, 1_400).status)
      .toBe("issued");
    expect(() => ceremonies.consumeForCertificateIssuance(ceremony.ceremonyId, expectedBinding, 1_500))
      .toThrow(expect.objectContaining({ code: "SECURITY_CEREMONY_REPLAY" }));
  });

  it("fails closed and burns a challenge when any transcript binding changes", () => {
    const ceremonies = store();
    ceremonies.issueInvitation(1_000);
    const ceremony = ceremonies.begin({
      invitationId,
      csrSha256: csrHash,
      ownerCredentialId: null,
      nowMs: 1_100,
    });
    const wrongToken = new Uint8Array(pairingToken());
    wrongToken[0] = 99;

    expect(() => ceremonies.takeForWebAuthnVerification(
      ceremony.challenge,
      binding("first-owner-registration", invitationId, wrongToken),
      1_200,
    )).toThrow(expect.objectContaining({ code: "SECURITY_CEREMONY_INVALID" }));
    expect(() => ceremonies.takeForWebAuthnVerification(
      ceremony.challenge,
      binding("first-owner-registration"),
      1_201,
    )).toThrow(expect.objectContaining({ code: "SECURITY_CEREMONY_REPLAY" }));
  });

  it("invalidates the previous invitation and rejects exact-expiry use", () => {
    const ceremonies = store([invitationId, secondInvitationId]);
    const first = ceremonies.issueInvitation(10);
    const second = ceremonies.issueInvitation(20);
    expect(() => ceremonies.begin({
      invitationId: first.invitationId,
      csrSha256: csrHash,
      ownerCredentialId: null,
      nowMs: 30,
    })).toThrow(expect.objectContaining({ code: "SECURITY_CEREMONY_REPLAY" }));
    expect(() => ceremonies.begin({
      invitationId: second.invitationId,
      csrSha256: csrHash,
      ownerCredentialId: null,
      nowMs: second.expiresAtMs,
    })).toThrow(expect.objectContaining({ code: "SECURITY_CEREMONY_EXPIRED" }));
  });

  it("generates a fresh 32-byte pairing token per ceremony", () => {
    const ceremonies = store([invitationId, ceremonyId, secondInvitationId, "550e8400-e29b-41d4-a716-446655440003"]);
    const first = ceremonies.issueInvitation(10);
    const firstCeremony = ceremonies.begin({
      invitationId: first.invitationId,
      csrSha256: csrHash,
      ownerCredentialId: null,
      nowMs: 20,
    });
    const second = ceremonies.issueInvitation(30);
    const secondCeremony = ceremonies.begin({
      invitationId: second.invitationId,
      csrSha256: csrHash,
      ownerCredentialId: null,
      nowMs: 40,
    });
    expect(firstCeremony.pairingToken).not.toBe(secondCeremony.pairingToken);
    expect(Buffer.from(firstCeremony.pairingToken, "base64url")).toHaveLength(32);
    expect(Buffer.from(secondCeremony.pairingToken, "base64url")).toHaveLength(32);
  });
});
