import {
  createHash,
  createPrivateKey,
  createPublicKey,
  randomBytes,
  sign as signBytes,
  type KeyObject,
} from "node:crypto";
import { canonicalizeJson, decodeUtf8Strict, type JsonObject } from "@pimobile/protocol";
import {
  CONTROL_MESSAGE_MAX_BYTES,
  PROOF_LIFETIME_MS,
  RENDEZVOUS_LIFETIME_MS,
  RelayError,
  type RelayClock,
  type RelayEntropy,
} from "./types.js";

const ID_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;
const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;
const RFC3339_TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const AUDIENCES = new Set(["control", "route-admin", "device-data", "mac-data"]);
const NOTICE_MODES = new Set(["normal", "pairing_provisional"]);

export type RouteAudience = "control" | "device-data" | "mac-data" | "route-admin";
export type RendezvousMode = "normal" | "pairing_provisional";

export interface RouteSigned {
  readonly audience: RouteAudience;
  readonly routeId: string;
  readonly keyId: string;
  readonly nonce: string;
  readonly expiresAt: string;
  readonly rendezvousId?: string;
}

export interface RouteProof {
  readonly type?: "route.proof";
  readonly signed: RouteSigned;
  readonly signature: string;
}

export interface RouteNotice {
  readonly type: "route.notice";
  readonly rendezvousId: string;
  readonly nonce: string;
  readonly expiresAt: string;
  readonly expiresAtMs: number;
  readonly mode: RendezvousMode;
}

export interface P256RouteSigner {
  readonly keyId: string;
  publicKeySpki(): Promise<Uint8Array>;
  signSha256(canonical: Uint8Array): Promise<Uint8Array>;
}

export const SYSTEM_RELAY_ENTROPY: RelayEntropy = {
  randomBytes: (size) => randomBytes(size),
};

export class NodeP256RouteSigner implements P256RouteSigner {
  readonly keyId: string;
  private readonly privateKey: KeyObject;
  private readonly spki: Uint8Array;

  constructor(keyId: string, privateKey: KeyObject | string | Uint8Array) {
    assertOpaqueId(keyId, "keyId");
    this.privateKey = privateKey instanceof Uint8Array
      ? createPrivateKey(Buffer.from(privateKey))
      : typeof privateKey === "string"
        ? createPrivateKey(privateKey)
        : privateKey;
    assertP256PrivateKey(this.privateKey);
    this.keyId = keyId;
    this.spki = exportAndValidateP256Spki(createPublicKey(this.privateKey));
  }

  publicKeySpki(): Promise<Uint8Array> {
    return Promise.resolve(this.spki.slice());
  }

  signSha256(canonical: Uint8Array): Promise<Uint8Array> {
    const signature = signBytes("sha256", canonical, { key: this.privateKey, dsaEncoding: "der" });
    return Promise.resolve(signature);
  }
}

export async function createRouteProof(
  signed: RouteSigned,
  signer: P256RouteSigner,
  includeType: boolean,
): Promise<RouteProof> {
  if (signed.keyId !== signer.keyId) {
    throw new RelayError("RELAY_BAD_KEY", "proof key does not match signer");
  }
  const canonical = new TextEncoder().encode(canonicalRouteSigned(signed));
  const signature = await signer.signSha256(canonical);
  assertDerP256Signature(signature);
  const proof = { signed, signature: encodeBase64Url(signature) } satisfies Omit<RouteProof, "type">;
  return includeType ? { type: "route.proof", ...proof } : proof;
}

export function createFreshSigned(
  audience: "route-admin",
  routeId: string,
  keyId: string,
  clock: RelayClock,
  entropy: RelayEntropy = SYSTEM_RELAY_ENTROPY,
): RouteSigned {
  assertOpaqueId(routeId, "routeId");
  assertOpaqueId(keyId, "keyId");
  const nonce = entropy.randomBytes(32);
  if (nonce.byteLength !== 32) {
    throw new RelayError("RELAY_BAD_PROOF", "relay entropy returned a wrong-length nonce");
  }
  return {
    audience,
    routeId,
    keyId,
    nonce: encodeBase64Url(nonce),
    expiresAt: new Date(clock.nowMs() + PROOF_LIFETIME_MS).toISOString(),
  };
}

export function createMacDataSigned(notice: RouteNotice, routeId: string, keyId: string): RouteSigned {
  assertOpaqueId(routeId, "routeId");
  assertOpaqueId(keyId, "keyId");
  return {
    audience: "mac-data",
    routeId,
    keyId,
    rendezvousId: notice.rendezvousId,
    nonce: notice.nonce,
    expiresAt: notice.expiresAt,
  };
}

export function parseControlChallenge(raw: unknown, isBinary: unknown, nowMs: number): RouteSigned {
  const value = parseTextObject(raw, isBinary);
  if (!Object.hasOwn(value, "signed") || value["type"] !== "route.challenge") {
    throw new RelayError("RELAY_AUTH_REJECTED", "expected relay challenge");
  }
  return parseSigned(value["signed"], nowMs, PROOF_LIFETIME_MS, "control");
}

export function parseControlReady(raw: unknown, isBinary: unknown, routeId: string, keyId: string): void {
  const value = parseTextObject(raw, isBinary);
  if (value["type"] !== "route.control.ready") {
    throw new RelayError("RELAY_AUTH_REJECTED", "expected relay control ready");
  }
  const hasRouteId = Object.hasOwn(value, "routeId");
  const hasKeyId = Object.hasOwn(value, "keyId");
  if (!hasRouteId && !hasKeyId) return;
  if (!hasRouteId || !hasKeyId || value["routeId"] !== routeId || value["keyId"] !== keyId) {
    throw new RelayError("RELAY_AUTH_REJECTED", "relay control ready binding mismatch");
  }
}

export function parseRouteNotice(raw: unknown, isBinary: unknown, nowMs: number): RouteNotice {
  const value = parseTextObject(raw, isBinary);
  if (value["type"] !== "route.notice") {
    throw new RelayError("RELAY_BAD_NOTICE", "unexpected relay control message");
  }
  const rendezvousId = requireString(value["rendezvousId"], "rendezvousId");
  const nonce = requireString(value["nonce"], "nonce");
  const expiresAt = requireString(value["expiresAt"], "expiresAt");
  const mode = requireString(value["mode"], "mode");
  assertOpaqueId(rendezvousId, "rendezvousId");
  assertNonce(nonce);
  const expiresAtMs = parseExpiry(expiresAt, nowMs, RENDEZVOUS_LIFETIME_MS, "notice");
  if (!NOTICE_MODES.has(mode)) {
    throw new RelayError("RELAY_BAD_NOTICE", "unsupported rendezvous mode");
  }
  return {
    type: "route.notice",
    rendezvousId,
    nonce,
    expiresAt,
    expiresAtMs,
    mode: mode as RendezvousMode,
  };
}

/** Parses a relay provisional-pairing control notification; returns undefined for other control messages. */
export function parsePairingRequestNotification(raw: unknown, isBinary: unknown): string | undefined {
  const value = parseTextObject(raw, isBinary);
  if (value["type"] !== "pairing.request") return undefined;
  const pairingId = requireString(value["pairingId"], "pairingId");
  assertOpaqueId(pairingId, "pairingId");
  return pairingId;
}

export function assertControlChallenge(
  signed: RouteSigned,
  routeId: string,
  keyId: string,
): void {
  if (
    signed.audience !== "control" ||
    signed.routeId !== routeId ||
    signed.keyId !== keyId ||
    signed.rendezvousId !== undefined
  ) {
    throw new RelayError("RELAY_AUTH_REJECTED", "relay challenge binding mismatch");
  }
}

export function assertP256Spki(spki: Uint8Array): void {
  if (spki.byteLength === 0 || spki.byteLength > 1_024) {
    throw new RelayError("RELAY_BAD_KEY", "route public key has invalid size");
  }
  let key: KeyObject;
  try {
    key = createPublicKey({ key: Buffer.from(spki), format: "der", type: "spki" });
  } catch {
    throw new RelayError("RELAY_BAD_KEY", "route public key is not SPKI DER");
  }
  if (key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") {
    throw new RelayError("RELAY_BAD_KEY", "route public key must be P-256");
  }
}

export function assertOpaqueId(value: string, field: string): void {
  if (!ID_PATTERN.test(value)) {
    throw new RelayError("RELAY_BAD_PROOF", `${field} is invalid`);
  }
}

export function encodeBase64Url(value: Uint8Array): string {
  return Buffer.from(value).toString("base64url");
}

export function canonicalRouteSigned(signed: RouteSigned): string {
  const value: JsonObject = {
    audience: signed.audience,
    routeId: signed.routeId,
    keyId: signed.keyId,
    nonce: signed.nonce,
    expiresAt: signed.expiresAt,
  };
  if (signed.rendezvousId !== undefined) value["rendezvousId"] = signed.rendezvousId;
  return canonicalizeJson(value);
}

function parseSigned(value: unknown, nowMs: number, maximumLifetimeMs: number, expectedAudience?: RouteAudience): RouteSigned {
  if (!isRecord(value)) throw new RelayError("RELAY_BAD_PROOF", "signed proof must be an object");
  const allowed = value["rendezvousId"] === undefined
    ? ["audience", "expiresAt", "keyId", "nonce", "routeId"]
    : ["audience", "expiresAt", "keyId", "nonce", "rendezvousId", "routeId"];
  assertExactKeys(value, allowed, "signed proof");
  const audience = requireString(value["audience"], "audience");
  const routeId = requireString(value["routeId"], "routeId");
  const keyId = requireString(value["keyId"], "keyId");
  const nonce = requireString(value["nonce"], "nonce");
  const expiresAt = requireString(value["expiresAt"], "expiresAt");
  if (!AUDIENCES.has(audience) || expectedAudience !== undefined && audience !== expectedAudience) {
    throw new RelayError("RELAY_BAD_PROOF", "proof audience is invalid");
  }
  assertOpaqueId(routeId, "routeId");
  assertOpaqueId(keyId, "keyId");
  assertNonce(nonce);
  parseExpiry(expiresAt, nowMs, maximumLifetimeMs, "proof");
  const rendezvousValue = value["rendezvousId"];
  if (rendezvousValue === undefined) {
    return { audience: audience as RouteAudience, routeId, keyId, nonce, expiresAt };
  }
  const rendezvousId = requireString(rendezvousValue, "rendezvousId");
  assertOpaqueId(rendezvousId, "rendezvousId");
  return { audience: audience as RouteAudience, routeId, keyId, nonce, expiresAt, rendezvousId };
}

function parseTextObject(raw: unknown, isBinary: unknown): Record<string, unknown> {
  if (isBinary !== false) throw new RelayError("RELAY_AUTH_REJECTED", "relay control requires text frames");
  let bytes: Uint8Array;
  if (typeof raw === "string") bytes = Buffer.from(raw, "utf8");
  else if (raw instanceof ArrayBuffer) bytes = new Uint8Array(raw);
  else if (ArrayBuffer.isView(raw)) bytes = new Uint8Array(raw.buffer, raw.byteOffset, raw.byteLength);
  else throw new RelayError("RELAY_AUTH_REJECTED", "relay control frame has invalid representation");
  if (bytes.byteLength === 0 || bytes.byteLength > CONTROL_MESSAGE_MAX_BYTES) {
    throw new RelayError("RELAY_AUTH_REJECTED", "relay control frame exceeds bounds");
  }
  let value: unknown;
  try {
    value = JSON.parse(decodeUtf8Strict(bytes));
  } catch {
    throw new RelayError("RELAY_AUTH_REJECTED", "relay control frame is invalid JSON");
  }
  if (!isRecord(value)) throw new RelayError("RELAY_AUTH_REJECTED", "relay control frame must be an object");
  return value;
}

function parseExpiry(value: string, nowMs: number, maximumLifetimeMs: number, kind: "notice" | "proof"): number {
  if (!RFC3339_TIMESTAMP_PATTERN.test(value)) {
    throw new RelayError(kind === "notice" ? "RELAY_BAD_NOTICE" : "RELAY_BAD_PROOF", `${kind} expiry is invalid`);
  }
  const expiresAtMs = Date.parse(value);
  // Server-issued challenges/notices get skew+latency leeway on the upper bound;
  // the lower bound (already expired) stays strict.
  const skewAllowanceMs = 2_000;
  if (!Number.isFinite(expiresAtMs) || expiresAtMs <= nowMs || expiresAtMs - nowMs > maximumLifetimeMs + skewAllowanceMs) {
    throw new RelayError(kind === "notice" ? "RELAY_BAD_NOTICE" : "RELAY_BAD_PROOF", `${kind} expiry is outside bounds`);
  }
  return expiresAtMs;
}

function assertNonce(value: string): void {
  if (value.length !== 43 || !BASE64URL_PATTERN.test(value)) {
    throw new RelayError("RELAY_BAD_PROOF", "proof nonce is invalid");
  }
  let decoded: Buffer;
  try {
    decoded = Buffer.from(value, "base64url");
  } catch {
    throw new RelayError("RELAY_BAD_PROOF", "proof nonce is invalid");
  }
  if (decoded.byteLength !== 32 || encodeBase64Url(decoded) !== value) {
    throw new RelayError("RELAY_BAD_PROOF", "proof nonce is invalid");
  }
}

function assertDerP256Signature(signature: Uint8Array): void {
  const bytes = Buffer.from(signature);
  if (bytes.byteLength < 8 || bytes.byteLength > 72 || bytes[0] !== 0x30 || bytes[1] !== bytes.byteLength - 2) {
    throw new RelayError("RELAY_BAD_PROOF", "route signature is not bounded DER");
  }
  let offset = 2;
  for (let index = 0; index < 2; index += 1) {
    if (bytes[offset] !== 0x02) throw new RelayError("RELAY_BAD_PROOF", "route signature is not DER ECDSA");
    const length = bytes[offset + 1];
    if (length === undefined || length < 1 || length > 33 || offset + 2 + length > bytes.byteLength) {
      throw new RelayError("RELAY_BAD_PROOF", "route signature integer is invalid");
    }
    const first = bytes[offset + 2];
    const second = bytes[offset + 3];
    if (first === undefined || first >= 0x80 || first === 0 && length > 1 && second !== undefined && second < 0x80) {
      throw new RelayError("RELAY_BAD_PROOF", "route signature integer is not canonical");
    }
    offset += 2 + length;
  }
  if (offset !== bytes.byteLength) throw new RelayError("RELAY_BAD_PROOF", "route signature has trailing bytes");
}

function assertP256PrivateKey(key: KeyObject): void {
  if (key.type !== "private" || key.asymmetricKeyType !== "ec" || key.asymmetricKeyDetails?.namedCurve !== "prime256v1") {
    throw new RelayError("RELAY_BAD_KEY", "route private key must be P-256");
  }
}

function exportAndValidateP256Spki(key: KeyObject): Uint8Array {
  const spki = key.export({ format: "der", type: "spki" });
  assertP256Spki(spki);
  return spki;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function assertExactKeys(value: Record<string, unknown>, expected: readonly string[], kind: string): void {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new RelayError("RELAY_BAD_PROOF", `${kind} contains unexpected fields`);
  }
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== "string") throw new RelayError("RELAY_BAD_PROOF", `${field} must be a string`);
  return value;
}

export function sha256(value: Uint8Array): Uint8Array {
  return createHash("sha256").update(value).digest();
}
