import { describe, expect, it } from "vitest";
import {
  PASSKEY_RP_ID,
  generateOwnerAssertionOptions,
  generateOwnerRegistrationOptions,
  type StoredOwnerCredential,
} from "../src/security/webauthn.js";

const challenge = Buffer.alloc(32, 12).toString("base64url");
const credential: StoredOwnerCredential = {
  id: "credential_id",
  publicKey: new Uint8Array([1, 2, 3]),
  counter: 0,
  transports: ["internal"],
};

describe("real SimpleWebAuthn option encoding", () => {
  it("preserves the bound 32-byte challenge instead of base64url-encoding its text", async () => {
    const registration = await generateOwnerRegistrationOptions({
      userId: new Uint8Array(32).fill(1),
      userName: "owner",
      challenge,
    });
    const assertion = await generateOwnerAssertionOptions({ challenge, credentials: [credential] });

    expect(registration.challenge).toBe(challenge);
    expect(registration.rp.id).toBe(PASSKEY_RP_ID);
    expect(registration.pubKeyCredParams).toEqual([{ alg: -7, type: "public-key" }]);
    expect(registration.authenticatorSelection).toMatchObject({
      residentKey: "required",
      requireResidentKey: true,
      userVerification: "required",
    });
    expect(assertion.challenge).toBe(challenge);
    expect(assertion.rpId).toBe(PASSKEY_RP_ID);
    expect(assertion.userVerification).toBe("required");
  });
});
