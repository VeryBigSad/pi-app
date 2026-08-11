import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { SecurityError } from "./security-error.js";

export const PAIRING_INVITATION_TTL_MS = 5 * 60 * 1000;
const CHALLENGE_LABEL = Buffer.from("pimobile-webauthn-pair-v1\0", "utf8");
const SHORT_CODE_LABEL = Buffer.from("pimobile-short-code-v1\0", "utf8");
const SHA256_BYTES = 32;

type CeremonyStatus =
  | "challenge_pending"
  | "challenge_consumed"
  | "webauthn_verified"
  | "local_confirmed"
  | "issued"
  | "failed";

export interface PairingInvitation {
  readonly invitationId: string;
  readonly nonce: string;
  readonly createdAtMs: number;
  readonly expiresAtMs: number;
}

interface CeremonyBase {
  readonly ceremonyId: string;
  readonly invitationId: string;
  readonly challenge: string;
  readonly pairingToken: string;
  readonly sessionBinding: string;
  readonly csrSha256: string;
  readonly shortCode: string;
  readonly createdAtMs: number;
  readonly expiresAtMs: number;
  readonly status: CeremonyStatus;
}

export interface FirstOwnerRegistrationCeremony extends CeremonyBase {
  readonly kind: "first-owner-registration";
}

export interface LaterDeviceAssertionCeremony extends CeremonyBase {
  readonly kind: "later-device-assertion";
  readonly ownerCredentialId: string;
}

export type PairingCeremony = FirstOwnerRegistrationCeremony | LaterDeviceAssertionCeremony;

export interface CeremonyBinding {
  readonly kind: PairingCeremony["kind"];
  readonly invitationId: string;
  readonly pairingToken: Uint8Array;
  readonly csrSha256: Uint8Array;
}

export interface BeginCeremonyInput {
  readonly invitationId: string;
  readonly csrSha256: Uint8Array;
  readonly ownerCredentialId: string | null;
  readonly nowMs?: number;
}

export class PairingCeremonyStore {
  private readonly random: (size: number) => Uint8Array;
  private readonly generateId: () => string;
  private activeInvitation: PairingInvitation | undefined;
  private activeCeremony: PairingCeremony | undefined;

  constructor(
    random: (size: number) => Uint8Array = (size) => randomBytes(size),
    generateId: () => string = randomUUID,
  ) {
    this.random = random;
    this.generateId = generateId;
  }

  issueInvitation(nowMs = Date.now()): PairingInvitation {
    validateTime(nowMs);
    const invitationId = this.generateId();
    const uuidBytes = parseUuid(invitationId);
    if (uuidBytes.length !== 16) throw invalid("invitation generator returned an invalid UUID");
    const nonce = exactBytes(this.random(SHA256_BYTES), SHA256_BYTES, "invitation nonce");
    const invitation = {
      invitationId,
      nonce: Buffer.from(nonce).toString("base64url"),
      createdAtMs: nowMs,
      expiresAtMs: nowMs + PAIRING_INVITATION_TTL_MS,
    };
    this.activeInvitation = invitation;
    this.activeCeremony = undefined;
    return { ...invitation };
  }

  begin(input: BeginCeremonyInput): PairingCeremony {
    const nowMs = input.nowMs ?? Date.now();
    validateTime(nowMs);
    const invitation = this.requireInvitation(input.invitationId, nowMs);
    if (this.activeCeremony !== undefined) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "invitation already has a ceremony");
    }
    const pairingToken = exactBytes(this.random(SHA256_BYTES), SHA256_BYTES, "pairing token");
    const csrSha256 = exactBytes(input.csrSha256, SHA256_BYTES, "CSR hash");
    const ownerCredentialId = input.ownerCredentialId;
    if (ownerCredentialId !== null) validateCredentialId(ownerCredentialId);
    const kind = ownerCredentialId === null ? "first-owner-registration" : "later-device-assertion";
    const ceremonyId = this.generateId();
    parseUuid(ceremonyId);
    const challengeNonce = exactBytes(this.random(SHA256_BYTES), SHA256_BYTES, "challenge nonce");
    const bindingBytes = encodeBinding(kind, invitation.invitationId, pairingToken, csrSha256);
    const challengeBytes = sha256(CHALLENGE_LABEL, bindingBytes, challengeNonce);
    const challenge = challengeBytes.toString("base64url");
    const shortCodeDigest = sha256(SHORT_CODE_LABEL, bindingBytes, challengeBytes);
    const shortCode = (shortCodeDigest.readUInt32BE(0) % 1_000_000).toString().padStart(6, "0");
    const base: CeremonyBase = {
      ceremonyId,
      invitationId: invitation.invitationId,
      challenge,
      pairingToken: Buffer.from(pairingToken).toString("base64url"),
      sessionBinding: sha256(pairingToken).toString("hex"),
      csrSha256: Buffer.from(csrSha256).toString("hex"),
      shortCode,
      createdAtMs: nowMs,
      expiresAtMs: invitation.expiresAtMs,
      status: "challenge_pending",
    };
    const ceremony: PairingCeremony = ownerCredentialId === null
      ? { ...base, kind: "first-owner-registration" }
      : { ...base, kind: "later-device-assertion", ownerCredentialId };
    this.activeCeremony = ceremony;
    return copyCeremony(ceremony);
  }

  takeForWebAuthnVerification(challenge: string, binding: CeremonyBinding, nowMs = Date.now()): PairingCeremony {
    validateTime(nowMs);
    const ceremony = this.activeCeremony;
    if (ceremony?.status !== "challenge_pending" || ceremony.challenge !== challenge) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "pairing challenge is unavailable");
    }
    const consumed: PairingCeremony = { ...ceremony, status: "challenge_consumed" };
    this.activeCeremony = consumed;
    if (nowMs >= ceremony.expiresAtMs) {
      this.activeCeremony = { ...ceremony, status: "failed" };
      throw new SecurityError("SECURITY_CEREMONY_EXPIRED", "pairing challenge expired");
    }
    if (!bindingMatches(ceremony, binding)) {
      this.activeCeremony = { ...ceremony, status: "failed" };
      throw invalid("pairing challenge binding does not match");
    }
    return copyCeremony(consumed);
  }

  recordWebAuthnResult(ceremonyId: string, verified: boolean): PairingCeremony {
    const ceremony = this.requireCeremony(ceremonyId, "challenge_consumed");
    const updated: PairingCeremony = { ...ceremony, status: verified ? "webauthn_verified" : "failed" };
    this.activeCeremony = updated;
    if (!verified) throw invalid("WebAuthn ceremony was not verified");
    return copyCeremony(updated);
  }

  confirmLocally(ceremonyId: string, approved: boolean, nowMs = Date.now()): PairingCeremony {
    validateTime(nowMs);
    const ceremony = this.requireCeremony(ceremonyId, "webauthn_verified");
    if (nowMs >= ceremony.expiresAtMs) {
      this.activeCeremony = { ...ceremony, status: "failed" };
      throw new SecurityError("SECURITY_CEREMONY_EXPIRED", "pairing confirmation expired");
    }
    const updated: PairingCeremony = { ...ceremony, status: approved ? "local_confirmed" : "failed" };
    this.activeCeremony = updated;
    if (!approved) throw invalid("pairing was rejected locally");
    return copyCeremony(updated);
  }

  consumeForCertificateIssuance(
    ceremonyId: string,
    binding: CeremonyBinding,
    nowMs = Date.now(),
  ): PairingCeremony {
    validateTime(nowMs);
    const ceremony = this.requireCeremony(ceremonyId, "local_confirmed");
    this.requireInvitation(ceremony.invitationId, nowMs);
    if (!bindingMatches(ceremony, binding)) {
      this.activeCeremony = { ...ceremony, status: "failed" };
      this.activeInvitation = undefined;
      throw invalid("certificate issuance binding does not match");
    }
    this.activeInvitation = undefined;
    const issued: PairingCeremony = { ...ceremony, status: "issued" };
    this.activeCeremony = issued;
    return copyCeremony(issued);
  }

  private requireInvitation(invitationId: string, nowMs: number): PairingInvitation {
    const invitation = this.activeInvitation;
    if (invitation?.invitationId !== invitationId) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "pairing invitation is unavailable");
    }
    if (nowMs >= invitation.expiresAtMs) {
      this.activeInvitation = undefined;
      this.activeCeremony = undefined;
      throw new SecurityError("SECURITY_CEREMONY_EXPIRED", "pairing invitation expired");
    }
    return invitation;
  }

  private requireCeremony(ceremonyId: string, status: CeremonyStatus): PairingCeremony {
    const ceremony = this.activeCeremony;
    if (ceremony?.ceremonyId !== ceremonyId || ceremony.status !== status) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "pairing ceremony state is unavailable");
    }
    return ceremony;
  }
}

function bindingMatches(ceremony: PairingCeremony, binding: CeremonyBinding): boolean {
  try {
    if (binding.kind !== ceremony.kind || binding.invitationId !== ceremony.invitationId) return false;
    const pairingToken = exactBytes(binding.pairingToken, SHA256_BYTES, "pairing token");
    const csrHash = exactBytes(binding.csrSha256, SHA256_BYTES, "CSR hash");
    const expectedSessionBinding = Buffer.from(ceremony.sessionBinding, "hex");
    const expectedCsrHash = Buffer.from(ceremony.csrSha256, "hex");
    return timingSafeEqual(sha256(pairingToken), expectedSessionBinding) && timingSafeEqual(Buffer.from(csrHash), expectedCsrHash);
  } catch {
    return false;
  }
}

function encodeBinding(
  kind: PairingCeremony["kind"],
  invitationId: string,
  pairingToken: Uint8Array,
  csrSha256: Uint8Array,
): Buffer {
  const encodedKind = Buffer.from(kind, "utf8");
  return Buffer.concat([
    Buffer.from([encodedKind.length]),
    encodedKind,
    parseUuid(invitationId),
    Buffer.from(pairingToken),
    Buffer.from(csrSha256),
  ]);
}

function exactBytes(value: Uint8Array, length: number, field: string): Uint8Array {
  if (!(value instanceof Uint8Array) || value.byteLength !== length) {
    throw invalid(`${field} must be ${String(length)} bytes`);
  }
  return new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
}

function parseUuid(value: string): Buffer {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)) {
    throw invalid("ceremony identity must be a canonical lowercase UUID");
  }
  return Buffer.from(value.replaceAll("-", ""), "hex");
}

function validateCredentialId(value: string): void {
  if (!/^[A-Za-z0-9_-]{1,1024}$/.test(value)) throw invalid("owner credential identity is invalid");
}

function validateTime(value: number): void {
  if (!Number.isSafeInteger(value) || value < 0) throw invalid("ceremony time is invalid");
}

function sha256(...parts: readonly Uint8Array[]): Buffer {
  const hash = createHash("sha256");
  for (const part of parts) hash.update(part);
  return hash.digest();
}

function copyCeremony(ceremony: PairingCeremony): PairingCeremony {
  return { ...ceremony };
}

function invalid(message: string): SecurityError {
  return new SecurityError("SECURITY_CEREMONY_INVALID", message);
}
