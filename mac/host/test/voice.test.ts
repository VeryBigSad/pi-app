import { chmodSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { GroqTranscriber, VoiceError, parseRetryAfter } from "../src/voice/groq-client.js";
import { DEFAULT_VOICE_LIMITS, VoiceRateLedger, type VoiceLimits } from "../src/voice/rate-ledger.js";
import { VadChunker } from "../src/voice/vad-chunker.js";
import { TranscriptionQueue } from "../src/voice/transcription-queue.js";

function path(name: string): string {
  return join(mkdtempSync(join(tmpdir(), "pi-mobile-voice-")), name);
}

function limits(overrides: Partial<VoiceLimits>): VoiceLimits {
  return { ...DEFAULT_VOICE_LIMITS, ...overrides };
}

const speech = new Int16Array(320).fill(2_000);
const silence = new Int16Array(320);

describe("durable voice accounting", () => {
  it("bills every attempt for at least ten seconds and persists", () => {
    const database = path("voice.db");
    let ledger = new VoiceRateLedger(database);
    const first = ledger.reserve("chunk", 1.5, 1_000);
    expect(first).toMatchObject({ allowed: true, billedSeconds: 10 });
    ledger.close();
    ledger = new VoiceRateLedger(database);
    expect(ledger.totals(2_000)).toMatchObject({ attempts: 1, encodedSeconds: 1.5, billedSeconds: 10 });
    ledger.close();
  });

  it("opens a rolling limit only at its exact boundary", () => {
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ requestsPerMinute: 1 }));
    expect(ledger.reserve("a", 1, 1_000).allowed).toBe(true);
    expect(ledger.reserve("b", 1, 60_999)).toEqual({ allowed: false, code: "VOICE_RPM_LIMIT", resetAtMs: 61_000 });
    expect(ledger.reserve("c", 1, 61_000).allowed).toBe(true);
    ledger.close();
  });

  it("enforces UTC budget boundaries", () => {
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ usdPerUtcDay: 0.00012, usdPerUtcMonth: 1 }));
    const beforeMidnight = Date.UTC(2026, 0, 1, 23, 59, 59);
    expect(ledger.reserve("a", 10, beforeMidnight).allowed).toBe(true);
    expect(ledger.reserve("b", 10, beforeMidnight + 1).allowed).toBe(false);
    expect(ledger.reserve("c", 10, Date.UTC(2026, 0, 2)).allowed).toBe(true);
    ledger.close();
  });
});

describe("voice ledger clock robustness", () => {
  it("tolerates a backward clock jump without locking windows or losing history", () => {
    const base = Date.UTC(2026, 5, 10, 12);
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ requestsPerMinute: 1 }));
    expect(ledger.reserve("a", 1, base).allowed).toBe(true);
    const jumped = ledger.reserve("b", 1, base - 10 * 60 * 1_000);
    expect(jumped.allowed).toBe(true);
    expect(ledger.totals(base).attempts).toBe(2);
    const denied = ledger.reserve("c", 1, base + 1_000);
    expect(denied).toMatchObject({ allowed: false, code: "VOICE_RPM_LIMIT", resetAtMs: base + 60_000 });
    ledger.close();
  });

  it("tolerates a forward clock jump without deleting quota history", () => {
    const base = Date.UTC(2026, 5, 10, 12);
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ requestsPerMinute: 1 }));
    expect(ledger.reserve("a", 1, base).allowed).toBe(true);
    expect(ledger.reserve("b", 1, base + 40 * 24 * 60 * 60 * 1_000).allowed).toBe(true);
    expect(ledger.totals(base).attempts).toBe(1);
    ledger.close();
  });

  it("ignores future rows instead of locking windows until a future reset", () => {
    const base = Date.UTC(2026, 5, 10, 12);
    const ledger = new VoiceRateLedger(path("voice.db"), limits({ requestsPerMinute: 1 }));
    expect(ledger.reserve("past", 1, base).allowed).toBe(true);
    expect(ledger.reserve("skewed", 1, base + 3_600_000).allowed).toBe(true);
    expect(ledger.reserve("corrected", 1, base + 61_000).allowed).toBe(true);
    expect(ledger.totals(base).attempts).toBe(1);
    ledger.close();
  });
});

describe("Groq transport", () => {
  it("reserves every retry and follows bounded Retry-After", async () => {
    const key = path("groq-key");
    writeFileSync(key, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    const ledger = new VoiceRateLedger(path("voice.db"));
    const waits: number[] = [];
    let requests = 0;
    const client = new GroqTranscriber({
      ledger,
      keyPath: key,
      nowMs: () => 1_000,
      sleep: (milliseconds) => { waits.push(milliseconds); return Promise.resolve(); },
      fetch: () => {
        requests += 1;
        return Promise.resolve(requests === 1
          ? new Response("", { status: 429, headers: { "retry-after": "2" } })
          : Response.json({ text: "hello" }));
      },
    });
    await expect(client.transcribe("chunk", Buffer.alloc(32_000))).resolves.toBe("hello");
    expect(waits).toEqual([2_000]);
    expect(ledger.totals(1_000)).toMatchObject({ attempts: 2, encodedSeconds: 2, billedSeconds: 20 });
    ledger.close();
  });

  it("rejects permissive key files before reserving quota", async () => {
    const key = path("groq-key");
    writeFileSync(key, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    chmodSync(key, 0o644);
    const ledger = new VoiceRateLedger(path("voice.db"));
    const client = new GroqTranscriber({ ledger, keyPath: key, fetch: () => Promise.resolve(Response.json({ text: "bad" })) });
    await expect(client.transcribe("chunk", Buffer.alloc(32_000))).rejects.toMatchObject({ code: "VOICE_KEY_PERMISSIONS" });
    expect(ledger.totals(Date.now()).attempts).toBe(0);
    ledger.close();
  });

  it("parses both Retry-After forms", () => {
    expect(parseRetryAfter("1.5", 0)).toBe(1_500);
    expect(parseRetryAfter("Thu, 01 Jan 1970 00:00:05 GMT", 2_000)).toBe(3_000);
    expect(parseRetryAfter("invalid", 0)).toBeUndefined();
  });
});

describe("transcription queue", () => {
  it("keeps one request active, queues two, and rejects overflow", async () => {
    const releases: (() => void)[] = [];
    let active = 0;
    let maximum = 0;
    const queue = new TranscriptionQueue({
      transcribe: async (id) => {
        active += 1;
        maximum = Math.max(maximum, active);
        await new Promise<void>((resolve) => releases.push(resolve));
        active -= 1;
        return id;
      },
    });
    const one = queue.submit("one", Buffer.alloc(2));
    const two = queue.submit("two", Buffer.alloc(2));
    const three = queue.submit("three", Buffer.alloc(2));
    await expect(queue.submit("four", Buffer.alloc(2))).rejects.toMatchObject({ code: "VOICE_QUEUE_FULL" });
    releases.shift()?.();
    await expect(one).resolves.toBe("one");
    await Promise.resolve();
    releases.shift()?.();
    await expect(two).resolves.toBe("two");
    await Promise.resolve();
    releases.shift()?.();
    await expect(three).resolves.toBe("three");
    expect(maximum).toBe(1);
  });

  it("cancels active and queued work", async () => {
    const queue = new TranscriptionQueue({
      transcribe: (_id, _pcm, signal) => new Promise((_resolve, reject) => signal?.addEventListener("abort", () => reject(new VoiceError("VOICE_CANCELED", "canceled")), { once: true })),
    });
    const active = queue.submit("active", Buffer.alloc(2));
    const queued = queue.submit("queued", Buffer.alloc(2));
    queue.cancelAll();
    await expect(active).rejects.toMatchObject({ code: "VOICE_CANCELED" });
    await expect(queued).rejects.toMatchObject({ code: "VOICE_CANCELED" });
  });
});

describe("VAD chunking", () => {
  it("keeps pre-roll and prefers a silence boundary after eight seconds", () => {
    const vad = new VadChunker();
    for (let index = 0; index < 15; index += 1) expect(vad.push(silence)).toEqual([]);
    for (let index = 0; index < 385; index += 1) expect(vad.push(speech)).toEqual([]);
    let chunks = [] as ReturnType<VadChunker["push"]>;
    for (let index = 0; index < 10; index += 1) chunks = vad.push(silence);
    expect(chunks).toHaveLength(1);
    expect(chunks[0]?.encodedSeconds).toBe(8.2);
  });

  it("forces chunks at twelve seconds with bounded overlap", () => {
    const vad = new VadChunker();
    const chunks = [] as ReturnType<VadChunker["push"]>;
    for (let index = 0; index < 1_200; index += 1) chunks.push(...vad.push(speech));
    expect(chunks).toHaveLength(2);
    expect(chunks.every((chunk) => chunk.encodedSeconds <= 12)).toBe(true);
    expect(chunks[1]?.pcm16le.subarray(0, 640)).toEqual(chunks[0]?.pcm16le.subarray(-640));
  });

  it("flushes a final chunk after a silence-boundary emission exactly once", () => {
    const vad = new VadChunker();
    const chunks = [] as ReturnType<VadChunker["push"]>;
    for (let index = 0; index < 400; index += 1) chunks.push(...vad.push(speech));
    for (let index = 0; index < 10; index += 1) chunks.push(...vad.push(silence));
    expect(chunks).toHaveLength(1);
    expect(chunks[0]).toMatchObject({ sequence: 0, final: false, encodedSeconds: 8.2 });
    const flushed = vad.finish();
    expect(flushed).toHaveLength(1);
    expect(flushed[0]).toMatchObject({ sequence: 1, final: true });
    expect(vad.finish()).toEqual([]);
  });

  it("does not reflush forced-boundary overlap without new audio", () => {
    const vad = new VadChunker();
    const chunks = [] as ReturnType<VadChunker["push"]>;
    for (let index = 0; index < 600; index += 1) chunks.push(...vad.push(speech));
    expect(chunks).toHaveLength(1);
    expect(chunks[0]?.final).toBe(false);
    expect(vad.finish()).toEqual([]);
  });

  it("includes forced-boundary overlap when new audio follows", () => {
    const vad = new VadChunker();
    const chunks = [] as ReturnType<VadChunker["push"]>;
    for (let index = 0; index < 600; index += 1) chunks.push(...vad.push(speech));
    for (let index = 0; index < 5; index += 1) expect(vad.push(speech)).toEqual([]);
    const flushed = vad.finish();
    expect(flushed).toHaveLength(1);
    expect(flushed[0]).toMatchObject({ sequence: 1, final: true, encodedSeconds: 0.6 });
    expect(flushed[0]?.pcm16le.subarray(0, 16_000)).toEqual(chunks[0]?.pcm16le.subarray(-16_000));
  });

  it("cancel discards an open utterance so finish stays empty", () => {
    const vad = new VadChunker();
    for (let index = 0; index < 400; index += 1) vad.push(speech);
    for (let index = 0; index < 10; index += 1) vad.push(silence);
    vad.cancel();
    expect(vad.finish()).toEqual([]);
  });
});
