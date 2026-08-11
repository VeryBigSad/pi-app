import { vi, describe, expect, it, beforeEach } from "vitest";
import type {
  AuthenticationResponseJSON,
  RegistrationResponseJSON,
} from "@simplewebauthn/server";

const mocks = vi.hoisted(() => ({
  generateRegistrationOptions: vi.fn(),
  verifyRegistrationResponse: vi.fn(),
  generateAuthenticationOptions: vi.fn(),
  verifyAuthenticationResponse: vi.fn(),
}));

vi.mock("@simplewebauthn/server", () => mocks);

import {
  ANDROID_APPLICATION_ID,
  ANDROID_RELEASE_CERT_SHA256_BASE64URL,
  ANDROID_WEBAUTHN_ORIGIN,
  PASSKEY_RP_ID,
  generateOwnerAssertionOptions,
  generateOwnerRegistrationOptions,
  verifyOwnerAssertion,
  verifyOwnerRegistration,
  type StoredOwnerCredential,
} from "../src/security/webauthn.js";

const challenge = Buffer.alloc(32, 5).toString("base64url");
const credential: StoredOwnerCredential = {
  id: "credential_id",
  publicKey: new Uint8Array([1, 2, 3]),
  counter: 0,
  transports: ["internal"],
};
const registrationResponse = { id: "credential_id" } as RegistrationResponseJSON;
const assertionResponse = { id: "credential_id" } as AuthenticationResponseJSON;
const liveRevocations = { isRevoked: () => false };
const revoked = { isRevoked: () => true };

describe("Android WebAuthn verifier policy", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.generateRegistrationOptions.mockResolvedValue({ challenge });
    mocks.generateAuthenticationOptions.mockResolvedValue({ challenge });
  });

  it("pins the production Android origin and RP identity", () => {
    expect(ANDROID_APPLICATION_ID).toBe("io.github.verybigsad.pimobile");
    expect(PASSKEY_RP_ID).toBe("verybigsad.github.io");
    expect(ANDROID_RELEASE_CERT_SHA256_BASE64URL).toBe("zDZm83fOTCvWzhmkfzq-RxmsBA_WT_sRnwLprEvd1P4");
    expect(ANDROID_WEBAUTHN_ORIGIN).toBe(
      "android:apk-key-hash:zDZm83fOTCvWzhmkfzq-RxmsBA_WT_sRnwLprEvd1P4",
    );
  });

  it("generates registration and assertion options requiring UV and P-256 registration", async () => {
    await generateOwnerRegistrationOptions({
      userId: new Uint8Array(32).fill(1),
      userName: "owner",
      challenge,
      excludeCredentialIds: [credential.id],
    });
    await generateOwnerAssertionOptions({ challenge, credentials: [credential] });

    expect(mocks.generateRegistrationOptions).toHaveBeenCalledWith(expect.objectContaining({
      rpID: PASSKEY_RP_ID,
      challenge: new Uint8Array(Buffer.from(challenge, "base64url")),
      supportedAlgorithmIDs: [-7],
      authenticatorSelection: {
        residentKey: "required",
        requireResidentKey: true,
        userVerification: "required",
      },
    }));
    expect(mocks.generateAuthenticationOptions).toHaveBeenCalledWith(expect.objectContaining({
      rpID: PASSKEY_RP_ID,
      challenge: new Uint8Array(Buffer.from(challenge, "base64url")),
      userVerification: "required",
      allowCredentials: [{ id: credential.id, transports: ["internal"] }],
    }));
  });

  it("verifies registration with exact Android origin, RP, UP, UV, and ES256", async () => {
    mocks.verifyRegistrationResponse.mockResolvedValue({
      verified: true,
      registrationInfo: {
        userVerified: true,
        origin: ANDROID_WEBAUTHN_ORIGIN,
        rpID: PASSKEY_RP_ID,
        credential,
        credentialDeviceType: "multiDevice",
        credentialBackedUp: true,
      },
    });

    const result = await verifyOwnerRegistration({ response: registrationResponse, expectedChallenge: challenge });
    expect(result.credential.id).toBe(credential.id);
    expect(mocks.verifyRegistrationResponse).toHaveBeenCalledWith(expect.objectContaining({
      expectedChallenge: challenge,
      expectedOrigin: ANDROID_WEBAUTHN_ORIGIN,
      expectedRPID: PASSKEY_RP_ID,
      requireUserPresence: true,
      requireUserVerification: true,
      supportedAlgorithmIDs: [-7],
    }));
  });

  it("verifies assertions with required UV and fails before crypto for revocation", async () => {
    mocks.verifyAuthenticationResponse.mockResolvedValue({
      verified: true,
      authenticationInfo: {
        userVerified: true,
        origin: ANDROID_WEBAUTHN_ORIGIN,
        rpID: PASSKEY_RP_ID,
        newCounter: 0,
      },
    });

    await expect(verifyOwnerAssertion({
      response: assertionResponse,
      expectedChallenge: challenge,
      credential,
      revocations: liveRevocations,
    })).resolves.toEqual({ newCounter: 0 });
    expect(mocks.verifyAuthenticationResponse).toHaveBeenCalledWith(expect.objectContaining({
      expectedOrigin: ANDROID_WEBAUTHN_ORIGIN,
      expectedRPID: PASSKEY_RP_ID,
      requireUserVerification: true,
    }));

    vi.clearAllMocks();
    await expect(verifyOwnerAssertion({
      response: assertionResponse,
      expectedChallenge: challenge,
      credential,
      revocations: revoked,
    })).rejects.toMatchObject({ code: "SECURITY_REVOKED" });
    expect(mocks.verifyAuthenticationResponse).not.toHaveBeenCalled();
  });

  it("fails closed when the library returns a wrong origin or non-verified result", async () => {
    mocks.verifyAuthenticationResponse.mockResolvedValue({
      verified: true,
      authenticationInfo: {
        userVerified: true,
        origin: "https://verybigsad.github.io",
        rpID: PASSKEY_RP_ID,
        newCounter: 1,
      },
    });
    await expect(verifyOwnerAssertion({
      response: assertionResponse,
      expectedChallenge: challenge,
      credential,
      revocations: liveRevocations,
    })).rejects.toMatchObject({ code: "SECURITY_WEBAUTHN_REJECTED" });

    mocks.verifyRegistrationResponse.mockResolvedValue({ verified: false });
    await expect(verifyOwnerRegistration({
      response: registrationResponse,
      expectedChallenge: challenge,
    })).rejects.toMatchObject({ code: "SECURITY_WEBAUTHN_REJECTED" });
  });
});
