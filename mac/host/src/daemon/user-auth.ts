import { randomBytes, randomUUID } from "node:crypto";
import { wireJsonObject } from "./wire-json.js";
import { isJsonObject, type JsonObject } from "@pimobile/protocol";
import type {
  CompleteUserVerification,
  UserAuthenticationBinding,
  UserAuthenticationRuntime,
  VerifiedUserAuthentication,
} from "../gateway/types.js";
import {
  ANDROID_WEBAUTHN_ORIGIN,
  PASSKEY_RP_ID,
  generateOwnerAssertionOptions,
  verifyOwnerAssertion,
} from "../security/webauthn.js";
import { SecurityError } from "../security/security-error.js";
import type { SqliteRevocationRegistry } from "../security/revocation-registry.js";
import type { HostStore } from "./host-store.js";

const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const MAX_PENDING_CHALLENGES = 64;

interface PendingChallenge {
  readonly challenge: string;
  readonly ceremonyId: string;
  readonly expiresAtMs: number;
}

/** Production WebAuthn assertion runtime for normal (already-paired) connections. */
export class UserAuthenticationService implements UserAuthenticationRuntime {
  private readonly pending = new Map<string, PendingChallenge>();

  constructor(
    private readonly store: HostStore,
    private readonly revocations: SqliteRevocationRegistry,
    private readonly now: () => number = () => Date.now(),
    private readonly debugAndroidOrigins?: readonly string[],
  ) {}

  async assertionOptions(binding: UserAuthenticationBinding, signal: AbortSignal): Promise<JsonObject> {
    signal.throwIfAborted();
    const credentials = this.store.ownerCredentials();
    if (credentials.length === 0) throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "no owner passkey is registered");
    this.prune();
    if (this.pending.size >= MAX_PENDING_CHALLENGES) {
      throw new SecurityError("SECURITY_CEREMONY_INVALID", "too many pending assertions");
    }
    const challenge = Buffer.from(randomBytes(32)).toString("base64url");
    const ceremonyId = randomUUID();
    this.pending.set(this.key(binding), { challenge, ceremonyId, expiresAtMs: this.now() + CHALLENGE_TTL_MS });
    const publicKey = await generateOwnerAssertionOptions({ challenge, credentials });
    return {
      ceremonyId,
      binding: {
        ceremonyKind: "assertion",
        rpId: PASSKEY_RP_ID,
        origin: ANDROID_WEBAUTHN_ORIGIN,
        challenge,
        expiresAt: new Date(this.now() + CHALLENGE_TTL_MS).toISOString(),
      },
      publicKey: wireJsonObject(publicKey),
    };
  }

  async verifyAssertion(
    response: JsonObject,
    binding: UserAuthenticationBinding,
    complete: CompleteUserVerification,
    signal: AbortSignal,
  ): Promise<VerifiedUserAuthentication> {
    signal.throwIfAborted();
    const key = this.key(binding);
    const pending = this.pending.get(key);
    this.pending.delete(key);
    if (pending === undefined || pending.expiresAtMs <= this.now()) {
      throw new SecurityError("SECURITY_CEREMONY_EXPIRED", "assertion challenge is unavailable");
    }
    const credential = response["credential"];
    if (!isJsonObject(credential) || typeof credential["id"] !== "string") {
      throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "assertion credential is invalid");
    }
    const stored = this.store.findOwnerCredential(credential["id"]);
    if (stored === undefined) throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "assertion credential is unknown");
    const result = await verifyOwnerAssertion({
      response: credential as unknown as Parameters<typeof verifyOwnerAssertion>[0]["response"],
      expectedChallenge: pending.challenge,
      credential: stored,
      revocations: this.revocations,
      ...(this.debugAndroidOrigins === undefined ? {} : { allowedOrigins: this.debugAndroidOrigins }),
    });
    await this.store.updateOwnerCredentialCounter(stored.id, result.newCounter);
    return complete({
      userId: this.store.ownerUserId() ?? "owner",
      credentialId: stored.id,
    });
  }

  private key(binding: UserAuthenticationBinding): string {
    return `${binding.deviceId}:${binding.certificateId}:${String(binding.pathGeneration)}`;
  }

  private prune(): void {
    const now = this.now();
    for (const [key, pending] of this.pending) {
      if (pending.expiresAtMs <= now) this.pending.delete(key);
    }
  }
}
