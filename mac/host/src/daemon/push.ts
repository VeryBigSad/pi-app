import { randomBytes } from "node:crypto";
import type { SettlementNotice } from "./session-service.js";

export interface NtfyConfig {
  readonly url: string;
  readonly token?: string;
}

export interface RegisteredPushEndpoint {
  readonly deviceId: string;
  readonly endpointId: string;
  readonly distributor: string;
  readonly endpoint: string;
  readonly wakePublicKey?: string;
}

export type PushEndpointProvider = () => RegisteredPushEndpoint | undefined;

export interface PushPublisherStatus {
  readonly configured: boolean;
  readonly published: number;
  readonly failed: number;
  readonly skipped: number;
}

export interface PushPublisher {
  publishWake(notice: SettlementNotice): Promise<void>;
  status(): PushPublisherStatus;
}

const MAX_ENDPOINT_PATH_LENGTH = 512;
const PUBLISH_TIMEOUT_MS = 10_000;
const OPAQUE_WAKE_RANDOM_BYTES = 32;
const OPAQUE_WAKE_ID_LENGTH = 43;
const OPAQUE_WAKE_ID_PATTERN = /^[A-Za-z0-9_-]+$/u;

type OpaqueWakeIdProvider = () => string;

/**
 * Publishes opaque wake notifications to the device-registered UnifiedPush
 * endpoint on the configured ntfy distributor. UnifiedPush delivery is a
 * plain POST to the endpoint URL the device received from its distributor.
 * The body is exactly one cryptographically random, unpadded base64url wake ID;
 * it carries no session, timestamp, prompt, transcript, terminal, or result data.
 *
 * Follow-up: if the ntfy UnifiedPush gateway is ever configured to require
 * VAPID webpush encryption for `wakePublicKey`, add aes128gcm encryption of
 * the wake body; ntfy UP publishes are plain POSTs, so it is not implemented.
 */
export class NtfyPushPublisher implements PushPublisher {
  private readonly baseHost: string | undefined;
  private published = 0;
  private failed = 0;
  private skipped = 0;
  private lastPublishedKey: string | undefined;
  private readonly inFlightKeys = new Set<string>();

  constructor(
    private readonly config: NtfyConfig | undefined,
    private readonly endpointProvider: PushEndpointProvider = () => undefined,
    private readonly wakeIdProvider: OpaqueWakeIdProvider = createOpaqueWakeId,
  ) {
    if (config !== undefined) this.baseHost = validateConfig(config);
  }

  async publishWake(notice: SettlementNotice): Promise<void> {
    const config = this.config;
    if (config === undefined) return;
    const registered = this.endpointProvider();
    const target = registered === undefined ? undefined : this.resolveTarget(registered.endpoint);
    if (target === undefined) {
      this.skipped += 1;
      return;
    }
    const dedupeKey = `${notice.sessionId}:${notice.streamEpoch}:${notice.sequence}`;
    if (dedupeKey === this.lastPublishedKey || this.inFlightKeys.has(dedupeKey)) return;
    const body = this.wakeIdProvider();
    if (!isOpaqueWakeId(body)) {
      this.failed += 1;
      return;
    }
    const headers: Record<string, string> = { "Content-Type": "application/octet-stream" };
    if (config.token !== undefined) headers["Authorization"] = `Bearer ${config.token}`;
    this.inFlightKeys.add(dedupeKey);
    try {
      const response = await fetch(target, {
        method: "POST",
        redirect: "error",
        headers,
        body,
        signal: AbortSignal.timeout(PUBLISH_TIMEOUT_MS),
      });
      await response.body?.cancel().catch(() => undefined);
      if (!response.ok) {
        this.failed += 1;
        return;
      }
      this.lastPublishedKey = dedupeKey;
      this.published += 1;
    } catch {
      this.failed += 1;
    } finally {
      this.inFlightKeys.delete(dedupeKey);
    }
  }

  status(): PushPublisherStatus {
    return {
      configured: this.config !== undefined,
      published: this.published,
      failed: this.failed,
      skipped: this.skipped,
    };
  }

  private resolveTarget(endpoint: string): string | undefined {
    let url: URL;
    try {
      url = new URL(endpoint);
    } catch {
      return undefined;
    }
    if (
      url.protocol !== "https:"
      || url.host !== this.baseHost
      || url.pathname.length === 0
      || url.pathname.length > MAX_ENDPOINT_PATH_LENGTH
      || url.username !== ""
      || url.password !== ""
    ) {
      return undefined;
    }
    return url.toString();
  }
}

function createOpaqueWakeId(): string {
  return randomBytes(OPAQUE_WAKE_RANDOM_BYTES).toString("base64url");
}

function isOpaqueWakeId(value: string): boolean {
  return value.length === OPAQUE_WAKE_ID_LENGTH && OPAQUE_WAKE_ID_PATTERN.test(value);
}

function validateConfig(config: NtfyConfig): string {
  let url: URL;
  try {
    url = new URL(config.url);
  } catch {
    throw new TypeError("ntfy url is invalid");
  }
  if (url.protocol !== "https:") throw new TypeError("ntfy config is invalid");
  return url.host;
}

export function parseNtfyConfig(value: unknown): NtfyConfig | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new TypeError("ntfy config must be an object");
  }
  const record = value as Record<string, unknown>;
  const url = record["url"];
  const token = record["token"];
  if (typeof url !== "string") throw new TypeError("ntfy config requires url");
  if (token !== undefined && typeof token !== "string") throw new TypeError("ntfy token must be a string");
  const config: NtfyConfig = {
    url,
    ...(typeof token === "string" ? { token } : {}),
  };
  validateConfig(config);
  return config;
}
