import { createHash, randomBytes } from "node:crypto";
import { logWarn } from "./log.js";
import { wireJsonObject } from "./wire-json.js";
import { Pkcs10CertificateRequest } from "@peculiar/x509";
import { isJsonObject, type Envelope, type JsonObject } from "@pimobile/protocol";
import type { PairingContext, PairingResult, PairingRuntime } from "../gateway/types.js";
import type { PairingCeremony, PairingCeremonyStore } from "../security/pairing-ceremony.js";
import {
  ANDROID_WEBAUTHN_ORIGIN,
  PASSKEY_RP_ID,
  generateOwnerAssertionOptions,
  generateOwnerRegistrationOptions,
  verifyOwnerAssertion,
  verifyOwnerRegistration,
  type StoredOwnerCredential,
} from "../security/webauthn.js";
import { issueDeviceCertificate } from "../security/pki.js";
import { SecurityError } from "../security/security-error.js";
import type { SqliteRevocationRegistry } from "../security/revocation-registry.js";
import type { HostStore } from "./host-store.js";
import type { CertificateAuthorityMaterial } from "../security/pki.js";

const SHA256 = /^[0-9a-f]{64}$/;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BASE64URL = /^[A-Za-z0-9_-]+$/;
const MAX_CSR_DER_BYTES = 16 * 1024;
const CONFIRMATION_POLL_MS = 250;

export interface PairingStatus {
  readonly state:
    | "idle"
    | "invitation_open"
    | "challenge_pending"
    | "awaiting_local_confirmation"
    | "issued"
    | "failed";
  readonly invitationId?: string;
  readonly shortCode?: string;
  readonly deviceId?: string;
}

export interface PairingCoordinatorOptions {
  readonly ceremonies: PairingCeremonyStore;
  readonly store: HostStore;
  readonly authority: () => CertificateAuthorityMaterial;
  readonly revocations: SqliteRevocationRegistry;
  readonly now?: () => number;
  readonly debugAndroidOrigins?: readonly string[];
  readonly onDevicePaired?: (device: { deviceId: string; deviceRouteKeyId?: string; deviceRoutePublicKey?: string }) => void;
}

/** Production pairing runtime: invitation → WebAuthn ceremony → local confirmation → CSR issuance. */
export class PairingCoordinator implements PairingRuntime {
  private readonly now: () => number;
  private pendingCeremony: PairingCeremony | undefined;
  private pendingDeviceRouteKeyId: string | undefined;
  private pendingDeviceRoutePublicKey: string | undefined;
  private confirmationWaiter: { readonly ceremonyId: string; resolve: (approved: boolean) => void } | undefined;
  private lastIssuedDeviceId: string | undefined;
  private failedAtMs: number | undefined;
  private openInvitation: { readonly invitationId: string; readonly expiresAtMs: number } | undefined;

  constructor(private readonly options: PairingCoordinatorOptions) {
    this.now = options.now ?? (() => Date.now());
  }

  issueInvitation(): { invitationId: string; nonce: string; expiresAtMs: number } {
    const invitation = this.options.ceremonies.issueInvitation(this.now());
    this.openInvitation = { invitationId: invitation.invitationId, expiresAtMs: invitation.expiresAtMs };
    this.pendingCeremony = undefined;
    this.pendingDeviceRouteKeyId = undefined;
    this.pendingDeviceRoutePublicKey = undefined;
    this.lastIssuedDeviceId = undefined;
    this.failedAtMs = undefined;
    return invitation;
  }

  activeInvitationId(): string | undefined {
    try {
      const status = this.status();
      return status.state === "invitation_open" || status.state === "challenge_pending" || status.state === "awaiting_local_confirmation"
        ? status.invitationId
        : undefined;
    } catch {
      return undefined;
    }
  }

  status(): PairingStatus {
    const pending = this.pendingCeremony;
    if (pending !== undefined) {
      if (pending.status === "webauthn_verified") {
        return { state: "awaiting_local_confirmation", invitationId: pending.invitationId, shortCode: pending.shortCode };
      }
      if (pending.status === "local_confirmed") {
        return { state: "awaiting_local_confirmation", invitationId: pending.invitationId, shortCode: pending.shortCode };
      }
      return { state: "challenge_pending", invitationId: pending.invitationId };
    }
    const open = this.openInvitation;
    if (open !== undefined && open.expiresAtMs > this.now()) {
      return { state: "invitation_open", invitationId: open.invitationId };
    }
    if (this.lastIssuedDeviceId !== undefined) return { state: "issued", deviceId: this.lastIssuedDeviceId };
    if (this.failedAtMs !== undefined) return { state: "failed" };
    return { state: "idle" };
  }

  /** Local Mac confirmation entry point used by the CLI over the admin socket. */
  confirmLocally(approved: boolean): PairingStatus {
    const pending = this.pendingCeremony;
    if (pending === undefined) throw new SecurityError("SECURITY_CEREMONY_INVALID", "no pairing ceremony is pending");
    try {
      const confirmed = this.options.ceremonies.confirmLocally(pending.ceremonyId, approved, this.now());
      this.pendingCeremony = confirmed;
    } catch (error) {
      this.failedAtMs = this.now();
      this.confirmationWaiter?.resolve(false);
      this.confirmationWaiter = undefined;
      throw error;
    }
    this.confirmationWaiter?.resolve(approved);
    this.confirmationWaiter = undefined;
    return this.status();
  }

  async handle(message: Envelope, context: PairingContext): Promise<PairingResult> {
    const body = message.body;
    logWarn("pairing", `ceremony message ${message.type}`);
    switch (message.type) {
      case "pair.begin":
        return await this.beginPairing(body, context);
      case "auth.registration.response":
        return await this.registrationResponse(body, context);
      case "auth.assertion.response":
        return await this.assertionResponse(body, context);
      case "pair.csr":
        return await this.certificateSigningRequest(body, context);
      case "pair.confirm":
        return { replies: [{ type: "pair.confirm", body: this.confirmBody(context, "waiting") }] };
      default:
        throw new SecurityError("SECURITY_CEREMONY_INVALID", "unsupported pairing message");
    }
  }

  async cancel(context: PairingContext): Promise<void> {
    void context;
    this.confirmationWaiter?.resolve(false);
    this.confirmationWaiter = undefined;
    if (this.pendingCeremony?.status !== "issued") this.failedAtMs = this.failedAtMs ?? this.now();
    this.pendingCeremony = undefined;
    this.openInvitation = undefined;
    await Promise.resolve();
  }

  private async beginPairing(body: JsonObject, context: PairingContext): Promise<PairingResult> {
    const csrSha256Hex = requireHex(body["csrSha256"], "csrSha256");
    const invitationId = body["invitationId"];
    if (invitationId !== context.invitationId) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "pairing invitation mismatch");
    }
    const deviceRouteKeyId = body["deviceRouteKeyId"];
    const deviceRoutePublicKey = body["deviceRoutePublicKey"];
    if (
      typeof deviceRouteKeyId !== "string" || !/^[A-Za-z0-9._-]{1,128}$/.test(deviceRouteKeyId)
      || typeof deviceRoutePublicKey !== "string" || !BASE64URL.test(deviceRoutePublicKey)
    ) {
      throw new SecurityError("SECURITY_CEREMONY_INVALID", "pair.begin device route key is invalid");
    }
    const ownerCredentialId = this.options.store.ownerCredentials()[0]?.id ?? null;
    const ceremony = this.options.ceremonies.begin({
      invitationId: context.invitationId,
      csrSha256: Buffer.from(csrSha256Hex, "hex"),
      ownerCredentialId,
      nowMs: this.now(),
    });
    this.pendingCeremony = ceremony;
    this.pendingDeviceRouteKeyId = deviceRouteKeyId;
    this.pendingDeviceRoutePublicKey = deviceRoutePublicKey;
    const binding = this.bindingBody(ceremony);
    if (ceremony.kind === "first-owner-registration") {
      const handle = Buffer.from(randomBytes(32)).toString("base64url");
      const user = await this.options.store.ensureOwnerUser(
        () => Buffer.from(randomBytes(32)).toString("base64url"),
        handle,
      );
      const publicKey = await generateOwnerRegistrationOptions({
        userId: Buffer.from(user.userId, "base64url"),
        userName: "Pi Mobile Owner",
        challenge: ceremony.challenge,
      });
      return {
        replies: [{
          type: "auth.registration.options",
          body: {
            ceremonyId: ceremony.ceremonyId,
            pairingToken: ceremony.pairingToken,
            binding,
            publicKey: wireJsonObject(publicKey),
          },
        }],
      };
    }
    const credentials = this.options.store.ownerCredentials();
    const publicKey = await generateOwnerAssertionOptions({ challenge: ceremony.challenge, credentials });
    return {
      replies: [{
        type: "auth.assertion.options",
        body: {
          ceremonyId: ceremony.ceremonyId,
          binding,
          publicKey: wireJsonObject(publicKey),
        },
      }],
    };
  }

  private async registrationResponse(body: JsonObject, context: PairingContext): Promise<PairingResult> {
    const ceremony = this.requirePending();
    if (ceremony.kind !== "first-owner-registration") {
      throw new SecurityError("SECURITY_CEREMONY_INVALID", "registration is not expected");
    }
    const credential = body["credential"];
    if (!isJsonObject(credential)) throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "registration credential is invalid");
    this.options.ceremonies.takeForWebAuthnVerification(ceremony.challenge, this.ceremonyBinding(ceremony, context), this.now());
    let verified;
    try {
      verified = await verifyOwnerRegistration({
        response: credential as unknown as Parameters<typeof verifyOwnerRegistration>[0]["response"],
        expectedChallenge: ceremony.challenge,
        ...(this.options.debugAndroidOrigins === undefined ? {} : { allowedOrigins: this.options.debugAndroidOrigins }),
      });
    } catch (error) {
      return this.authFailureReply(ceremony, error);
    }
    const after = this.options.ceremonies.recordWebAuthnResult(ceremony.ceremonyId, true);
    this.pendingCeremony = after;
    await this.options.store.addOwnerCredential(verified.credential);
    return { replies: [{ type: "auth.result", body: this.authResultBody(after) }] };
  }

  private async assertionResponse(body: JsonObject, context: PairingContext): Promise<PairingResult> {
    const ceremony = this.requirePending();
    const credential = body["credential"];
    if (!isJsonObject(credential) || typeof credential["id"] !== "string") {
      throw new SecurityError("SECURITY_WEBAUTHN_REJECTED", "assertion credential is invalid");
    }
    const stored = this.options.store.findOwnerCredential(credential["id"]);
    if (stored === undefined) {
      return this.authFailureReply(ceremony, new SecurityError("SECURITY_WEBAUTHN_REJECTED", "assertion credential is unknown"));
    }
    this.options.ceremonies.takeForWebAuthnVerification(ceremony.challenge, this.ceremonyBinding(ceremony, context), this.now());
    let result;
    try {
      result = await verifyOwnerAssertion({
        response: credential as unknown as Parameters<typeof verifyOwnerAssertion>[0]["response"],
        expectedChallenge: ceremony.challenge,
        credential: stored,
        revocations: this.options.revocations,
        ...(this.options.debugAndroidOrigins === undefined ? {} : { allowedOrigins: this.options.debugAndroidOrigins }),
      });
    } catch (error) {
      return this.authFailureReply(ceremony, error);
    }
    await this.options.store.updateOwnerCredentialCounter(stored.id, result.newCounter);
    const after = this.options.ceremonies.recordWebAuthnResult(ceremony.ceremonyId, true);
    this.pendingCeremony = after;
    return { replies: [{ type: "auth.result", body: this.authResultBody(after) }] };
  }

  private async certificateSigningRequest(body: JsonObject, context: PairingContext): Promise<PairingResult> {
    const ceremony = this.requirePending();
    if (body["invitationId"] !== ceremony.invitationId) {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "pairing invitation mismatch");
    }
    const csrSha256Hex = requireHex(body["csrSha256"], "csrSha256");
    if (csrSha256Hex !== ceremony.csrSha256) {
      throw new SecurityError("SECURITY_CEREMONY_INVALID", "CSR hash does not match pair.begin");
    }
    const csrDer = decodeCsrDer(body["csrDer"]);
    const deviceId = csrCommonName(csrDer);
    const confirmed = await this.awaitLocalConfirmation(ceremony);
    if (!confirmed) throw new SecurityError("SECURITY_CEREMONY_INVALID", "pairing was rejected locally");
    const issued = this.options.ceremonies.consumeForCertificateIssuance(
      ceremony.ceremonyId,
      this.ceremonyBinding(ceremony, context),
      this.now(),
    );
    const certificate = await issueDeviceCertificate(this.options.authority(), deviceId, csrDer, new Date(this.now()));
    const certificateId = createHash("sha256").update(Buffer.from(certificate.certificate.rawData)).digest("hex");
    await this.options.store.addDevice({
      deviceId,
      certificateId,
      ...(this.pendingDeviceRouteKeyId === undefined ? {} : { deviceRouteKeyId: this.pendingDeviceRouteKeyId }),
      createdAtMs: this.now(),
    });
    this.pendingCeremony = issued;
    this.openInvitation = undefined;
    this.lastIssuedDeviceId = deviceId;
    this.options.onDevicePaired?.({
      deviceId,
      ...(this.pendingDeviceRouteKeyId === undefined ? {} : { deviceRouteKeyId: this.pendingDeviceRouteKeyId }),
      ...(this.pendingDeviceRoutePublicKey === undefined ? {} : { deviceRoutePublicKey: this.pendingDeviceRoutePublicKey }),
    });
    return {
      certificateIssued: true,
      replies: [
        { type: "pair.confirm", body: this.confirmBody(context, "confirmed") },
        {
          type: "pair.result",
          body: {
            invitationId: ceremony.invitationId,
            deviceId,
            deviceCertificateChain: [certificate.certificatePem, this.options.authority().certificate.toString("pem")],
            routeKeyId: this.pendingDeviceRouteKeyId ?? "",
          },
        },
      ],
    };
  }

  private requirePending(): PairingCeremony {
    const pending = this.pendingCeremony;
    if (pending === undefined || pending.status === "failed" || pending.status === "issued") {
      throw new SecurityError("SECURITY_CEREMONY_REPLAY", "no active pairing ceremony");
    }
    return pending;
  }

  private awaitLocalConfirmation(ceremony: PairingCeremony): Promise<boolean> {
    const latest = this.pendingCeremony;
    if (latest?.ceremonyId === ceremony.ceremonyId && latest.status === "local_confirmed") {
      return Promise.resolve(true);
    }
    return new Promise<boolean>((resolveWait) => {
      this.confirmationWaiter = { ceremonyId: ceremony.ceremonyId, resolve: resolveWait };
      const deadline = ceremony.expiresAtMs;
      const poll = (): void => {
        if (this.confirmationWaiter?.ceremonyId !== ceremony.ceremonyId) return;
        if (this.now() >= deadline) {
          this.confirmationWaiter = undefined;
          this.failedAtMs = this.now();
          resolveWait(false);
          return;
        }
        const timer = setTimeout(poll, CONFIRMATION_POLL_MS);
        timer.unref();
      };
      const timer = setTimeout(poll, CONFIRMATION_POLL_MS);
      timer.unref();
    });
  }

  private ceremonyBinding(ceremony: PairingCeremony, context: PairingContext) {
    return {
      kind: ceremony.kind,
      invitationId: context.invitationId,
      pairingToken: Buffer.from(ceremony.pairingToken, "base64url"),
      csrSha256: Buffer.from(ceremony.csrSha256, "hex"),
    };
  }

  private bindingBody(ceremony: PairingCeremony): JsonObject {
    return {
      ceremonyKind: ceremony.kind === "first-owner-registration" ? "registration" : "assertion",
      invitationId: ceremony.invitationId,
      sessionBinding: ceremony.sessionBinding,
      csrSha256: ceremony.csrSha256,
      rpId: PASSKEY_RP_ID,
      origin: ANDROID_WEBAUTHN_ORIGIN,
      challenge: ceremony.challenge,
      expiresAt: new Date(ceremony.expiresAtMs).toISOString(),
    };
  }

  /** Clean auth.result failure reply instead of killing the provisional connection. */
  private authFailureReply(ceremony: PairingCeremony, error: unknown): PairingResult {
    const code = error instanceof SecurityError ? error.code : "SECURITY_WEBAUTHN_REJECTED";
    logWarn("pairing", `ceremony ${ceremony.ceremonyId} rejected: ${code}`);
    const after = this.options.ceremonies.recordWebAuthnResult(ceremony.ceremonyId, false);
    this.pendingCeremony = after;
    return { replies: [{ type: "auth.result", body: { ceremonyId: ceremony.ceremonyId, success: false, error: code } }] };
  }

  private authResultBody(ceremony: PairingCeremony): JsonObject {
    return {
      ceremonyId: ceremony.ceremonyId,
      success: true,
      transcriptHash: createHash("sha256").update(`${ceremony.ceremonyId}:${ceremony.challenge}`, "utf8").digest("hex"),
    };
  }

  private confirmBody(context: PairingContext, status: "waiting" | "confirmed" | "rejected"): JsonObject {
    const pending = this.pendingCeremony;
    const transcriptHash = pending === undefined
      ? createHash("sha256").update(context.invitationId, "utf8").digest("hex")
      : createHash("sha256").update(`${pending.ceremonyId}:${pending.challenge}`, "utf8").digest("hex");
    return {
      invitationId: context.invitationId,
      status,
      transcriptHash,
      ...(pending === undefined ? {} : { shortCode: pending.shortCode }),
    };
  }
}

function requireHex(value: unknown, field: string): string {
  if (typeof value !== "string" || !SHA256.test(value)) {
    throw new SecurityError("SECURITY_CEREMONY_INVALID", `${field} is invalid`);
  }
  return value;
}

function decodeCsrDer(value: unknown): Uint8Array {
  if (typeof value !== "string" || !BASE64URL.test(value)) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR encoding is invalid");
  }
  const der = Buffer.from(value, "base64url");
  if (der.byteLength < 64 || der.byteLength > MAX_CSR_DER_BYTES) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR size is invalid");
  }
  return new Uint8Array(der.buffer, der.byteOffset, der.byteLength);
}

function csrCommonName(csrDer: Uint8Array): string {
  let request: Pkcs10CertificateRequest;
  try {
    request = new Pkcs10CertificateRequest(csrDer.slice());
  } catch (error) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR encoding is invalid", { cause: error });
  }
  const names = request.subjectName.getField("CN");
  const deviceId = names[0];
  if (names.length !== 1 || deviceId === undefined || !UUID_V4.test(deviceId)) {
    throw new SecurityError("SECURITY_CSR_INVALID", "CSR common name is not a device identity");
  }
  return deviceId;
}

export type { StoredOwnerCredential };
