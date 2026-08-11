import { createPublicKey, verify as verifySignature } from "node:crypto";
import { canonicalizeJson, type JsonObject } from "@pimobile/protocol";
import { SecurityError } from "./security-error.js";

export const PAIRING_INVITATION_URI_PREFIX = "pimobile://pair?v=1&d=";
export const PAIRING_INVITATION_MAX_JSON_BYTES = 2048;

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const BASE64URL_32 = /^[A-Za-z0-9_-]{43}$/;
const OPAQUE_ID = /^[A-Za-z0-9._-]{1,128}$/;
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const MAX_DIRECT_CANDIDATES = 16;

export interface PairingDirectCandidate {
  readonly host: string;
  readonly port: number;
}

export interface RelayPairingExchangeHint {
  readonly pairingId: string;
  readonly secret: string;
  readonly expiresAt: string;
}

/** Signed payload of a v1 pairing invitation, per protocol/schema/pairing-invitation.schema.json. */
export interface PairingInvitationPayload {
  readonly version: 1;
  readonly relayUrl: string;
  readonly routeId: string;
  readonly routeKeyId: string;
  readonly invitationId: string;
  readonly expiresAt: string;
  readonly nonce: string;
  readonly serverCertificateSha256: string;
  readonly directCandidates: readonly PairingDirectCandidate[];
  readonly macInstanceId: string;
  readonly relayPairing?: RelayPairingExchangeHint;
}

export interface SignedPairingInvitation {
  readonly signed: PairingInvitationPayload;
  readonly signature: string;
}

/** Structural P-256 signer over JCS-canonical bytes; satisfied by relay route signers. */
export interface PairingInvitationSigner {
  readonly keyId: string;
  signSha256(canonical: Uint8Array): Promise<Uint8Array>;
}

export async function signPairingInvitation(
  payload: PairingInvitationPayload,
  signer: PairingInvitationSigner,
): Promise<SignedPairingInvitation> {
  validateInvitationPayload(payload);
  if (signer.keyId !== payload.routeKeyId) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "invitation signer does not match routeKeyId");
  }
  const canonical = new TextEncoder().encode(canonicalizeJson(payload as unknown as JsonObject));
  if (canonical.byteLength > PAIRING_INVITATION_MAX_JSON_BYTES) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "pairing invitation exceeds size bounds");
  }
  const signature = await signer.signSha256(canonical);
  assertDerSignature(signature);
  return { signed: payload, signature: Buffer.from(signature).toString("base64url") };
}

export function encodePairingInvitationUri(invitation: SignedPairingInvitation): string {
  // The envelope must be JCS-canonical: Android verifies the decoded bytes
  // against their own canonicalization (key order signature < signed).
  const raw = Buffer.from(canonicalizeJson(invitation as unknown as JsonObject), "utf8");
  if (raw.byteLength > 2 * PAIRING_INVITATION_MAX_JSON_BYTES) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "pairing invitation exceeds size bounds");
  }
  return `${PAIRING_INVITATION_URI_PREFIX}${raw.toString("base64url")}`;
}

export function decodePairingInvitationUri(uri: string): SignedPairingInvitation {
  if (!uri.startsWith(PAIRING_INVITATION_URI_PREFIX)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "pairing invitation URI is invalid");
  }
  let value: unknown;
  try {
    value = JSON.parse(Buffer.from(uri.slice(PAIRING_INVITATION_URI_PREFIX.length), "base64url").toString("utf8"));
  } catch (error) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "pairing invitation URI is invalid", { cause: error });
  }
  if (!isRecord(value) || !isRecord(value["signed"]) || typeof value["signature"] !== "string") {
    throw new SecurityError("SECURITY_INVALID_INPUT", "pairing invitation payload is invalid");
  }
  const signed = value["signed"] as unknown as PairingInvitationPayload;
  validateInvitationPayload(signed);
  return { signed, signature: value["signature"] };
}

export function verifyPairingInvitation(invitation: SignedPairingInvitation, publicKeySpki: Uint8Array): boolean {
  try {
    validateInvitationPayload(invitation.signed);
    const signature = Buffer.from(invitation.signature, "base64url");
    assertDerSignature(signature);
    const key = createPublicKey({ key: Buffer.from(publicKeySpki), format: "der", type: "spki" });
    const canonical = Buffer.from(canonicalizeJson(invitation.signed as unknown as JsonObject), "utf8");
    return verifySignature("sha256", canonical, { key, dsaEncoding: "der" }, signature);
  } catch {
    return false;
  }
}

export function validateInvitationPayload(payload: PairingInvitationPayload): void {
  const relayPairing = payload.relayPairing;
  let relayUrl: URL;
  try {
    relayUrl = new URL(payload.relayUrl);
  } catch {
    throw invalid("invitation relayUrl is invalid");
  }
  if (
    (payload.version as number) !== 1
    || relayUrl.protocol !== "wss:" || payload.relayUrl.length > 512
    || !OPAQUE_ID.test(payload.routeId)
    || !OPAQUE_ID.test(payload.routeKeyId)
    || !UUID_V4.test(payload.invitationId)
    || !UUID_V4.test(payload.macInstanceId)
    || !RFC3339.test(payload.expiresAt) || payload.expiresAt.length > 64 || !Number.isFinite(Date.parse(payload.expiresAt))
    || !BASE64URL_32.test(payload.nonce)
    || !SHA256.test(payload.serverCertificateSha256)
    || !Array.isArray(payload.directCandidates) || payload.directCandidates.length > MAX_DIRECT_CANDIDATES
    || payload.directCandidates.some((candidate) => {
      if (!isRecord(candidate)) return true;
      const host = candidate["host"];
      const port = candidate["port"];
      return typeof host !== "string" || host.length === 0 || host.length > 253
        || !Number.isInteger(port) || (port as number) < 1 || (port as number) > 65535;
    })
    || (relayPairing !== undefined && (
      !OPAQUE_ID.test(relayPairing.pairingId)
      || !/^[A-Za-z0-9_-]{1,128}$/.test(relayPairing.secret)
      || !RFC3339.test(relayPairing.expiresAt) || !Number.isFinite(Date.parse(relayPairing.expiresAt))
    ))
  ) {
    throw invalid("pairing invitation payload is invalid");
  }
}

function assertDerSignature(signature: Uint8Array): void {
  if (
    signature.byteLength < 8 || signature.byteLength > 72
    || signature[0] !== 0x30 || signature[1] !== signature.byteLength - 2
  ) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "invitation signature is not bounded DER");
  }
}

function invalid(message: string): SecurityError {
  return new SecurityError("SECURITY_INVALID_INPUT", message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
