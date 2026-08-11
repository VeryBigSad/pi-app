import { homedir } from "node:os";
import { readFile, stat } from "node:fs/promises";
import { resolve } from "node:path";
import type { VoiceRateLedger } from "./rate-ledger.js";

export type VoiceErrorCode =
  | "VOICE_KEY_UNAVAILABLE"
  | "VOICE_KEY_PERMISSIONS"
  | "VOICE_QUOTA"
  | "VOICE_RETRY_AFTER_LONG"
  | "VOICE_RATE_LIMITED"
  | "VOICE_NETWORK"
  | "VOICE_RESPONSE_INVALID"
  | "VOICE_CANCELED"
  | "VOICE_QUEUE_FULL";

export class VoiceError extends Error {
  readonly code: VoiceErrorCode;
  readonly resetAtMs: number | undefined;

  constructor(code: VoiceErrorCode, message: string, resetAtMs?: number, options?: ErrorOptions) {
    super(message, options);
    this.name = "VoiceError";
    this.code = code;
    this.resetAtMs = resetAtMs;
  }
}

export interface GroqClientOptions {
  readonly ledger: VoiceRateLedger;
  readonly keyPath?: string;
  readonly fetch?: typeof fetch;
  readonly nowMs?: () => number;
  readonly sleep?: (milliseconds: number) => Promise<void>;
  readonly random?: () => number;
}

export class GroqTranscriber {
  private readonly ledger: VoiceRateLedger;
  private readonly keyPath: string;
  private readonly fetcher: typeof fetch;
  private readonly nowMs: () => number;
  private readonly sleep: (milliseconds: number) => Promise<void>;
  private readonly random: () => number;

  constructor(options: GroqClientOptions) {
    this.ledger = options.ledger;
    this.keyPath = options.keyPath ?? resolve(homedir(), ".groq_key");
    this.fetcher = options.fetch ?? fetch;
    this.nowMs = options.nowMs ?? Date.now;
    this.sleep = options.sleep ?? ((milliseconds) => new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds)));
    this.random = options.random ?? Math.random;
  }

  async transcribe(chunkId: string, pcm16le: Buffer, signal?: AbortSignal): Promise<string> {
    if (pcm16le.length === 0 || pcm16le.length % 2 !== 0 || pcm16le.length > 30 * 16_000 * 2) {
      throw new TypeError("PCM chunk is invalid");
    }
    const encodedSeconds = pcm16le.length / (16_000 * 2);
    let lastError: unknown;

    for (let attempt = 0; attempt < 4; attempt += 1) {
      throwIfCanceled(signal);
      const key = await readKey(this.keyPath);
      const reservation = this.ledger.reserve(chunkId, encodedSeconds, this.nowMs());
      if (!reservation.allowed) throw new VoiceError("VOICE_QUOTA", reservation.code, reservation.resetAtMs);

      try {
        const response = await this.fetcher("https://api.groq.com/openai/v1/audio/transcriptions", {
          method: "POST",
          headers: { Authorization: `Bearer ${key}` },
          body: multipart(wav(pcm16le)),
          signal: signal === undefined ? AbortSignal.timeout(60_000) : AbortSignal.any([signal, AbortSignal.timeout(60_000)]),
        });
        if (response.ok) {
          const value = await response.json() as { text?: unknown };
          if (typeof value.text !== "string") throw new VoiceError("VOICE_RESPONSE_INVALID", "transcription response is invalid");
          return value.text;
        }
        if (response.status !== 429 && response.status < 500) {
          throw new VoiceError("VOICE_NETWORK", `transcription failed with HTTP ${String(response.status)}`);
        }
        if (attempt === 3) throw new VoiceError("VOICE_RATE_LIMITED", "transcription retries exhausted");
        await this.waitBeforeRetry(response.headers.get("retry-after"), attempt, signal);
      } catch (error) {
        if (error instanceof VoiceError) throw error;
        throwIfCanceled(signal);
        lastError = error;
        if (attempt === 3) break;
        await this.waitBeforeRetry(null, attempt, signal);
      }
    }
    throw new VoiceError("VOICE_NETWORK", "transcription network failure", undefined, { cause: lastError });
  }

  private async waitBeforeRetry(retryAfter: string | null, attempt: number, signal?: AbortSignal): Promise<void> {
    const headerDelay = parseRetryAfter(retryAfter, this.nowMs());
    if (headerDelay !== undefined) {
      if (headerDelay > 120_000) throw new VoiceError("VOICE_RETRY_AFTER_LONG", "server retry delay exceeds 120 seconds", this.nowMs() + headerDelay);
      await abortable(this.sleep(headerDelay), signal);
      return;
    }
    const maximum = Math.min(30_000, 1_000 * 2 ** attempt);
    await abortable(this.sleep(Math.floor(this.random() * maximum)), signal);
  }
}

export function parseRetryAfter(value: string | null, nowMs: number): number | undefined {
  if (value === null) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds * 1_000);
  const date = Date.parse(value);
  if (!Number.isFinite(date)) return undefined;
  return Math.max(0, date - nowMs);
}

function throwIfCanceled(signal: AbortSignal | undefined): void {
  if (signal?.aborted === true) throw new VoiceError("VOICE_CANCELED", "transcription canceled");
}

async function abortable(operation: Promise<void>, signal: AbortSignal | undefined): Promise<void> {
  if (signal === undefined) return operation;
  throwIfCanceled(signal);
  await new Promise<void>((resolvePromise, reject) => {
    const cancel = (): void => reject(new VoiceError("VOICE_CANCELED", "transcription canceled"));
    signal.addEventListener("abort", cancel, { once: true });
    void operation.then(
      () => { signal.removeEventListener("abort", cancel); resolvePromise(); },
      (error: unknown) => { signal.removeEventListener("abort", cancel); reject(error); },
    );
  });
}

async function readKey(path: string): Promise<string> {
  let metadata;
  let value;
  try {
    [metadata, value] = await Promise.all([stat(path), readFile(path, "utf8")]);
  } catch (error) {
    throw new VoiceError("VOICE_KEY_UNAVAILABLE", "Groq key is unavailable", undefined, { cause: error });
  }
  if ((metadata.mode & 0o077) !== 0) throw new VoiceError("VOICE_KEY_PERMISSIONS", "Groq key permissions must be 0600 or stricter");
  const key = value.trim();
  if (key.length < 20 || /\s/u.test(key)) throw new VoiceError("VOICE_KEY_UNAVAILABLE", "Groq key is invalid");
  return key;
}

function multipart(audio: Buffer): FormData {
  const form = new FormData();
  form.append("file", new Blob([new Uint8Array(audio)], { type: "audio/wav" }), "voice.wav");
  form.append("model", "whisper-large-v3-turbo");
  form.append("response_format", "json");
  return form;
}

function wav(pcm: Buffer): Buffer {
  const output = Buffer.allocUnsafe(44 + pcm.length);
  output.write("RIFF", 0, "ascii");
  output.writeUInt32LE(36 + pcm.length, 4);
  output.write("WAVEfmt ", 8, "ascii");
  output.writeUInt32LE(16, 16);
  output.writeUInt16LE(1, 20);
  output.writeUInt16LE(1, 22);
  output.writeUInt32LE(16_000, 24);
  output.writeUInt32LE(32_000, 28);
  output.writeUInt16LE(2, 32);
  output.writeUInt16LE(16, 34);
  output.write("data", 36, "ascii");
  output.writeUInt32LE(pcm.length, 40);
  pcm.copy(output, 44);
  return output;
}
