import { describe, expect, it, vi, beforeEach } from "vitest";
import type {
  AuthenticationResponseJSON,
  RegistrationResponseJSON,
} from "@simplewebauthn/server";

const mocks = vi.hoisted(() => ({
  verifyRegistrationResponse: vi.fn(),
  verifyAuthenticationResponse: vi.fn(),
}));

vi.mock("@simplewebauthn/server", () => mocks);

import {
  ANDROID_WEBAUTHN_ORIGIN,
  normalizeAllowedOrigins,
  verifyOwnerAssertion,
  verifyOwnerRegistration,
  type StoredOwnerCredential,
} from "../src/security/webauthn.js";
import { SecurityError } from "../src/security/security-error.js";

const DEBUG_ORIGIN = "android:apk-key-hash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const challenge = Buffer.alloc(32, 5).toString("base64url");
const credential: StoredOwnerCredential = {
  id: "credential_id",
  publicKey: new Uint8Array([1, 2, 3]),
  counter: 0,
};
const registrationResponse = { id: "credential_id" } as RegistrationResponseJSON;
const assertionResponse = { id: "credential_id" } as AuthenticationResponseJSON;
const revocations = { isRevoked: () => false };

function registrationInfo(origin: string) {
  return {
    verified: true,
    registrationInfo: {
      userVerified: true,
      origin,
      rpID: "verybigsad.github.io",
      credential: { id: credential.id, publicKey: credential.publicKey, counter: 0 },
      credentialDeviceType: "singleDevice",
      credentialBackedUp: false,
    },
  };
}

function authenticationInfo(origin: string) {
  return {
    verified: true,
    authenticationInfo: { userVerified: true, origin, rpID: "verybigsad.github.io", newCounter: 1 },
  };
}

describe("WebAuthn origin allowlist", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("defaults to the release origin only", () => {
    expect(normalizeAllowedOrigins(undefined)).toEqual([ANDROID_WEBAUTHN_ORIGIN]);
    expect(normalizeAllowedOrigins([])).toEqual([ANDROID_WEBAUTHN_ORIGIN]);
  });

  it("rejects invalid or redundant allowlist entries", () => {
    expect(() => normalizeAllowedOrigins(["https://evil.example"])).toThrow(SecurityError);
    expect(() => normalizeAllowedOrigins([ANDROID_WEBAUTHN_ORIGIN])).toThrow(SecurityError);
    expect(normalizeAllowedOrigins([DEBUG_ORIGIN])).toEqual([ANDROID_WEBAUTHN_ORIGIN, DEBUG_ORIGIN]);
  });

  it("rejects a debug origin registration by default", async () => {
    mocks.verifyRegistrationResponse.mockResolvedValue(registrationInfo(DEBUG_ORIGIN));
    await expect(verifyOwnerRegistration({ response: registrationResponse, expectedChallenge: challenge }))
      .rejects.toThrow(SecurityError);
    expect(mocks.verifyRegistrationResponse).toHaveBeenCalledWith(expect.objectContaining({
      expectedOrigin: ANDROID_WEBAUTHN_ORIGIN,
    }));
  });

  it("accepts a debug origin registration when configured", async () => {
    mocks.verifyRegistrationResponse.mockResolvedValue(registrationInfo(DEBUG_ORIGIN));
    const result = await verifyOwnerRegistration({
      response: registrationResponse,
      expectedChallenge: challenge,
      allowedOrigins: [DEBUG_ORIGIN],
    });
    expect(result.credential.id).toBe(credential.id);
    expect(mocks.verifyRegistrationResponse).toHaveBeenCalledWith(expect.objectContaining({
      expectedOrigin: [ANDROID_WEBAUTHN_ORIGIN, DEBUG_ORIGIN],
    }));
  });

  it("rejects a debug origin assertion by default and accepts when configured", async () => {
    mocks.verifyAuthenticationResponse.mockResolvedValue(authenticationInfo(DEBUG_ORIGIN));
    await expect(verifyOwnerAssertion({
      response: assertionResponse,
      expectedChallenge: challenge,
      credential,
      revocations,
    })).rejects.toThrow(SecurityError);
    const result = await verifyOwnerAssertion({
      response: assertionResponse,
      expectedChallenge: challenge,
      credential,
      revocations,
      allowedOrigins: [DEBUG_ORIGIN],
    });
    expect(result.newCounter).toBe(1);
    expect(mocks.verifyAuthenticationResponse).toHaveBeenLastCalledWith(expect.objectContaining({
      expectedOrigin: [ANDROID_WEBAUTHN_ORIGIN, DEBUG_ORIGIN],
    }));
  });
});
