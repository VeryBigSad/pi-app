import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
  type AuthenticationResponseJSON,
  type AuthenticatorTransportFuture,
  type Base64URLString,
  type RegistrationResponseJSON,
  type WebAuthnCredential,
} from "@simplewebauthn/server";
import { SecurityError } from "./security-error.js";

export const PASSKEY_RP_ID = "verybigsad.github.io";
export const PASSKEY_RP_NAME = "Pi Mobile";
export const ANDROID_APPLICATION_ID = "io.github.verybigsad.pimobile";
export const ANDROID_RELEASE_CERT_SHA256_BASE64URL = "zDZm83fOTCvWzhmkfzq-RxmsBA_WT_sRnwLprEvd1P4";
export const ANDROID_WEBAUTHN_ORIGIN = `android:apk-key-hash:${ANDROID_RELEASE_CERT_SHA256_BASE64URL}`;
export const WEBAUTHN_CEREMONY_TIMEOUT_MS = 5 * 60 * 1000;

export interface StoredOwnerCredential {
  readonly id: Base64URLString;
  readonly publicKey: Uint8Array;
  readonly counter: number;
  readonly transports?: readonly AuthenticatorTransportFuture[];
}

export interface PasskeyRevocationChecker {
  isRevoked(kind: "passkey", id: string): boolean;
}

export async function generateOwnerRegistrationOptions(input: {
  readonly userId: Uint8Array;
  readonly userName: string;
  readonly challenge: string;
  readonly excludeCredentialIds?: readonly Base64URLString[];
}) {
  validateUser(input.userId, input.userName);
  const challengeBytes = decodeChallenge(input.challenge);
  const excluded = input.excludeCredentialIds ?? [];
  if (excluded.length > 64) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "registration credential set is invalid");
  }
  for (const id of excluded) validateCredentialId(id);
  return await generateRegistrationOptions({
    rpName: PASSKEY_RP_NAME,
    rpID: PASSKEY_RP_ID,
    userID: new Uint8Array(input.userId),
    userName: input.userName,
    userDisplayName: input.userName,
    challenge: challengeBytes,
    timeout: WEBAUTHN_CEREMONY_TIMEOUT_MS,
    attestationType: "none",
    excludeCredentials: excluded.map((id) => ({ id })),
    authenticatorSelection: {
      residentKey: "required",
      requireResidentKey: true,
      userVerification: "required",
    },
    supportedAlgorithmIDs: [-7],
  });
}

export async function verifyOwnerRegistration(input: {
  readonly response: RegistrationResponseJSON;
  readonly expectedChallenge: string;
  readonly allowedOrigins?: readonly string[];
}): Promise<{
  readonly credential: StoredOwnerCredential;
  readonly credentialDeviceType: "singleDevice" | "multiDevice";
  readonly credentialBackedUp: boolean;
}> {
  validateChallenge(input.expectedChallenge);
  const origins = normalizeAllowedOrigins(input.allowedOrigins);
  try {
    const result = await verifyRegistrationResponse({
      response: input.response,
      expectedChallenge: input.expectedChallenge,
      expectedOrigin: origins.length === 1 ? origins[0] : [...origins],
      expectedRPID: PASSKEY_RP_ID,
      expectedType: "webauthn.create",
      requireUserPresence: true,
      requireUserVerification: true,
      supportedAlgorithmIDs: [-7],
    });
    if (
      !result.verified ||
      !result.registrationInfo.userVerified ||
      !origins.includes(result.registrationInfo.origin) ||
      result.registrationInfo.rpID !== PASSKEY_RP_ID
    ) {
      throw rejected();
    }
    const credential = result.registrationInfo.credential;
    return {
      credential: copyCredential(credential),
      credentialDeviceType: result.registrationInfo.credentialDeviceType,
      credentialBackedUp: result.registrationInfo.credentialBackedUp,
    };
  } catch (error) {
    if (error instanceof SecurityError) throw error;
    throw rejected(error);
  }
}

export async function generateOwnerAssertionOptions(input: {
  readonly challenge: string;
  readonly credentials: readonly StoredOwnerCredential[];
}) {
  const challengeBytes = decodeChallenge(input.challenge);
  if (input.credentials.length === 0 || input.credentials.length > 64) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "assertion credential set is invalid");
  }
  for (const credential of input.credentials) validateStoredCredential(credential);
  return await generateAuthenticationOptions({
    rpID: PASSKEY_RP_ID,
    challenge: challengeBytes,
    timeout: WEBAUTHN_CEREMONY_TIMEOUT_MS,
    userVerification: "required",
    allowCredentials: input.credentials.map((credential) => ({
      id: credential.id,
      ...(credential.transports === undefined ? {} : { transports: [...credential.transports] }),
    })),
  });
}

export async function verifyOwnerAssertion(input: {
  readonly response: AuthenticationResponseJSON;
  readonly expectedChallenge: string;
  readonly credential: StoredOwnerCredential;
  readonly revocations: PasskeyRevocationChecker;
  readonly allowedOrigins?: readonly string[];
}): Promise<{ readonly newCounter: number }> {
  validateChallenge(input.expectedChallenge);
  const origins = normalizeAllowedOrigins(input.allowedOrigins);
  if (input.revocations.isRevoked("passkey", input.credential.id)) {
    throw new SecurityError("SECURITY_REVOKED", "owner credential is revoked");
  }
  if (input.response.id !== input.credential.id) throw rejected();
  try {
    const result = await verifyAuthenticationResponse({
      response: input.response,
      expectedChallenge: input.expectedChallenge,
      expectedOrigin: origins.length === 1 ? origins[0] : [...origins],
      expectedRPID: PASSKEY_RP_ID,
      expectedType: "webauthn.get",
      credential: toWebAuthnCredential(input.credential),
      requireUserVerification: true,
    });
    if (
      !result.verified ||
      !result.authenticationInfo.userVerified ||
      !origins.includes(result.authenticationInfo.origin) ||
      result.authenticationInfo.rpID !== PASSKEY_RP_ID
    ) {
      throw rejected();
    }
    return { newCounter: result.authenticationInfo.newCounter };
  } catch (error) {
    if (error instanceof SecurityError) throw error;
    throw rejected(error);
  }
}

const ANDROID_ORIGIN_PATTERN = /^android:apk-key-hash:[A-Za-z0-9_-]{1,128}$/;
const MAX_ANDROID_ORIGINS = 8;

/**
 * Allowed WebAuthn origins. Defaults to the release-signing origin only; extra entries
 * (debug APK signing identities for local development) must be explicitly configured
 * via the daemon's `debugAndroidOrigins` config key.
 */
export function normalizeAllowedOrigins(allowedOrigins?: readonly string[]): [string, ...string[]] {
  const extras = allowedOrigins ?? [];
  if (extras.length > MAX_ANDROID_ORIGINS) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "Android origin allowlist is too large");
  }
  const origins: string[] = [ANDROID_WEBAUTHN_ORIGIN];
  for (const origin of extras) {
    if (!ANDROID_ORIGIN_PATTERN.test(origin) || origin === ANDROID_WEBAUTHN_ORIGIN) {
      throw new SecurityError("SECURITY_INVALID_INPUT", "Android origin allowlist entry is invalid");
    }
    if (!origins.includes(origin)) origins.push(origin);
  }
  return origins as [string, ...string[]];
}

function validateUser(userId: Uint8Array, userName: string): void {
  if (!(userId instanceof Uint8Array) || userId.byteLength < 16 || userId.byteLength > 64) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "WebAuthn user identity is invalid");
  }
  if (userName.length === 0 || userName.length > 64 || hasControlCharacter(userName)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "WebAuthn user name is invalid");
  }
}

function hasControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code <= 31 || code === 127) return true;
  }
  return false;
}

function validateChallenge(challenge: string): void {
  decodeChallenge(challenge);
}

function decodeChallenge(challenge: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(challenge)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "WebAuthn challenge is invalid");
  }
  const decoded = Buffer.from(challenge, "base64url");
  if (decoded.length !== 32 || decoded.toString("base64url") !== challenge) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "WebAuthn challenge is invalid");
  }
  const copy = new Uint8Array(decoded.length);
  copy.set(decoded);
  decoded.fill(0);
  return copy;
}

function toWebAuthnCredential(credential: StoredOwnerCredential): WebAuthnCredential {
  validateStoredCredential(credential);
  return {
    id: credential.id,
    publicKey: new Uint8Array(credential.publicKey),
    counter: credential.counter,
    ...(credential.transports === undefined ? {} : { transports: [...credential.transports] }),
  };
}

function copyCredential(credential: WebAuthnCredential): StoredOwnerCredential {
  const copied: StoredOwnerCredential = {
    id: credential.id,
    publicKey: new Uint8Array(credential.publicKey),
    counter: credential.counter,
    ...(credential.transports === undefined ? {} : { transports: [...credential.transports] }),
  };
  validateStoredCredential(copied);
  return copied;
}

function validateStoredCredential(credential: StoredOwnerCredential): void {
  validateCredentialId(credential.id);
  if (
    !(credential.publicKey instanceof Uint8Array) ||
    credential.publicKey.byteLength === 0 ||
    credential.publicKey.byteLength > 2048 ||
    !Number.isSafeInteger(credential.counter) ||
    credential.counter < 0
  ) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "credential material is invalid");
  }
}

function validateCredentialId(id: string): void {
  if (!/^[A-Za-z0-9_-]{1,1024}$/.test(id)) {
    throw new SecurityError("SECURITY_INVALID_INPUT", "credential identity is invalid");
  }
}

function rejected(cause?: unknown): SecurityError {
  return new SecurityError(
    "SECURITY_WEBAUTHN_REJECTED",
    "WebAuthn verification rejected",
    cause === undefined ? undefined : { cause },
  );
}
