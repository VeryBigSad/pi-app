import type { SettlementNotice } from "./session-service.js";

export interface NtfyConfig {
  readonly url: string;
  readonly topic: string;
  readonly token?: string;
}

export interface PushPublisher {
  publishWake(notice: SettlementNotice): Promise<void>;
  status(): { configured: boolean; published: number; failed: number };
}

const MAX_TOPIC_LENGTH = 128;
const PUBLISH_TIMEOUT_MS = 10_000;

/**
 * Publishes opaque wake notifications to the configured ntfy distributor.
 * Payloads contain only catch-up metadata; never prompts, transcripts,
 * terminal bytes, or error text.
 */
export class NtfyPushPublisher implements PushPublisher {
  private published = 0;
  private failed = 0;
  private lastPublishedKey: string | undefined;

  constructor(private readonly config: NtfyConfig | undefined) {
    if (config !== undefined) validateConfig(config);
  }

  async publishWake(notice: SettlementNotice): Promise<void> {
    const config = this.config;
    if (config === undefined) return;
    const dedupeKey = `${notice.sessionId}:${notice.streamEpoch}:${notice.sequence}`;
    if (dedupeKey === this.lastPublishedKey) return;
    const body = JSON.stringify({
      v: 1,
      kind: "wake",
      settlementId: notice.settlementId,
      sessionId: notice.sessionId,
      streamEpoch: notice.streamEpoch,
      sequence: notice.sequence,
      atMs: notice.settledAtMs,
    });
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Title: "pi-mobile",
      Tags: "bell",
    };
    if (config.token !== undefined) headers["Authorization"] = `Bearer ${config.token}`;
    try {
      const response = await fetch(`${config.url.replace(/\/$/, "")}/${encodeURIComponent(config.topic)}`, {
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
    }
  }

  status(): { configured: boolean; published: number; failed: number } {
    return { configured: this.config !== undefined, published: this.published, failed: this.failed };
  }
}

function validateConfig(config: NtfyConfig): void {
  let url: URL;
  try {
    url = new URL(config.url);
  } catch {
    throw new TypeError("ntfy url is invalid");
  }
  if (url.protocol !== "https:" || config.topic.length === 0 || config.topic.length > MAX_TOPIC_LENGTH) {
    throw new TypeError("ntfy config is invalid");
  }
}

export function parseNtfyConfig(value: unknown): NtfyConfig | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new TypeError("ntfy config must be an object");
  }
  const record = value as Record<string, unknown>;
  const url = record["url"];
  const topic = record["topic"];
  const token = record["token"];
  if (typeof url !== "string" || typeof topic !== "string") throw new TypeError("ntfy config requires url and topic");
  if (token !== undefined && typeof token !== "string") throw new TypeError("ntfy token must be a string");
  const config: NtfyConfig = {
    url,
    topic,
    ...(typeof token === "string" ? { token } : {}),
  };
  validateConfig(config);
  return config;
}
