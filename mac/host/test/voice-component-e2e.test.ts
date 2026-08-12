import { chmodSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { encodeStreamPayload, type JsonObject } from "@pimobile/protocol";
import { describe, expect, it } from "vitest";
import { GatewayVoiceRuntime, VoiceService } from "../src/daemon/voice-service.js";
import { mergeCumulativeTranscript, VoiceStreamManager } from "../src/gateway/voice-stream.js";
import type { OutboundMessage } from "../src/gateway/types.js";

const streamId = "550e8400-e29b-41d4-a716-446655440070";
const startBody: JsonObject = { streamId, sampleRate: 16_000, channels: 1, sampleFormat: "s16le" };

function temporary(name: string): string {
  return join(mkdtempSync(join(tmpdir(), "pi-mobile-voice-e2e-")), name);
}

async function waitUntil(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error("voice component did not settle");
}

function deferred<T>(): { readonly promise: Promise<T>; readonly resolve: (value: T) => void; readonly reject: (error: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function sendChunk(manager: VoiceStreamManager, sequence: number, final: boolean): void {
  manager.audioPcm(encodeStreamPayload({ streamId, sequence, offset: BigInt(sequence * 2), data: new Uint8Array(2) }));
  manager.boundary({ sessionId: streamId, chunkSequence: String(sequence), final });
}

describe("synthetic PCM to fake Groq component", () => {
  it("assembles frames, uses the durable Groq queue, deduplicates overlap, and finishes cumulatively", async () => {
    const keyPath = temporary("groq-key");
    writeFileSync(keyPath, "gsk_abcdefghijklmnopqrstuvwxyz\n", { mode: 0o600 });
    chmodSync(keyPath, 0o600);
    let requests = 0;
    const voice = new VoiceService(temporary("voice.db"), {
      keyPath,
      fetch: (_input, init) => {
        expect(init?.body).toBeInstanceOf(FormData);
        requests += 1;
        return Promise.resolve(Response.json({ text: requests === 1 ? "hello world" : "world again" }));
      },
    });
    const emitted: OutboundMessage[] = [];
    const controller = new AbortController();
    const manager = new VoiceStreamManager(
      new GatewayVoiceRuntime(voice),
      (message) => { emitted.push(message); return Promise.resolve(); },
      controller.signal,
    );

    manager.start(startBody);
    manager.audioPcm(encodeStreamPayload({ streamId, sequence: 0, offset: 0n, data: new Uint8Array(640).fill(1) }));
    manager.boundary({ sessionId: streamId, chunkSequence: "0", final: false });
    manager.audioPcm(encodeStreamPayload({ streamId, sequence: 1, offset: 640n, data: new Uint8Array(640).fill(2) }));
    manager.boundary({ sessionId: streamId, chunkSequence: "1", final: true });

    await waitUntil(() => emitted.length === 3);
    expect(requests).toBe(2);
    expect(emitted).toEqual([
      { type: "voice.partial", body: { sessionId: streamId, chunkSequence: "0", revision: "1", text: "hello world" } },
      { type: "voice.partial", body: { sessionId: streamId, chunkSequence: "1", revision: "2", text: "hello world again" } },
      { type: "voice.finish", body: { sessionId: streamId, chunkSequence: "1", text: "hello world again" } },
    ]);
    voice.close();
  });

  it("terminally fails on the first chunk and suppresses queued late completions", async () => {
    const jobs: { readonly result: ReturnType<typeof deferred<string>>; readonly signal: AbortSignal }[] = [];
    const emitted: OutboundMessage[] = [];
    const manager = new VoiceStreamManager(
      { submit: (_chunk, signal) => {
        const result = deferred<string>();
        jobs.push({ result, signal });
        return result.promise;
      } },
      (message) => { emitted.push(message); return Promise.resolve(); },
      new AbortController().signal,
    );
    manager.start(startBody);
    sendChunk(manager, 0, false);
    sendChunk(manager, 1, false);
    sendChunk(manager, 2, true);
    jobs[0]?.result.reject(new Error("first failed"));
    await waitUntil(() => emitted.length === 1);
    expect(jobs.every((job) => job.signal.aborted)).toBe(true);
    jobs[1]?.result.resolve("late partial");
    jobs[2]?.result.resolve("late finish");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(emitted).toEqual([{ type: "voice.error", body: { streamId, code: "VOICE_FAILED", message: "Voice transcription failed" } }]);
  });

  it("terminally fails in the middle after one partial and suppresses a late finish", async () => {
    const jobs: { readonly result: ReturnType<typeof deferred<string>>; readonly signal: AbortSignal }[] = [];
    const emitted: OutboundMessage[] = [];
    const manager = new VoiceStreamManager(
      { submit: (_chunk, signal) => {
        const result = deferred<string>();
        jobs.push({ result, signal });
        return result.promise;
      } },
      (message) => { emitted.push(message); return Promise.resolve(); },
      new AbortController().signal,
    );
    manager.start(startBody);
    sendChunk(manager, 0, false);
    sendChunk(manager, 1, false);
    sendChunk(manager, 2, true);
    jobs[0]?.result.resolve("first words");
    await waitUntil(() => emitted.length === 1);
    jobs[1]?.result.reject(new Error("middle failed"));
    await waitUntil(() => emitted.length === 2);
    jobs[2]?.result.resolve("late finish");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(jobs.every((job) => job.signal.aborted)).toBe(true);
    expect(emitted).toEqual([
      { type: "voice.partial", body: { sessionId: streamId, chunkSequence: "0", revision: "1", text: "first words" } },
      { type: "voice.error", body: { streamId, code: "VOICE_FAILED", message: "Voice transcription failed" } },
    ]);
  });

  it.each([
    ["malformed PCM", (manager: VoiceStreamManager) => manager.audioPcm(new Uint8Array())],
    ["out-of-order PCM", (manager: VoiceStreamManager) => manager.audioPcm(encodeStreamPayload({ streamId, sequence: 2, offset: 2n, data: new Uint8Array(2) }))],
    ["malformed boundary", (manager: VoiceStreamManager) => manager.boundary({ sessionId: streamId, chunkSequence: "invalid", final: false })],
  ])("terminally fails %s, cancels queued work, and accepts a fresh stream", async (_name, fault) => {
    const job = deferred<string>();
    let jobSignal: AbortSignal | undefined;
    const emitted: OutboundMessage[] = [];
    const manager = new VoiceStreamManager(
      { submit: (_chunk, signal) => {
        jobSignal = signal;
        return job.promise;
      } },
      (message) => { emitted.push(message); return Promise.resolve(); },
      new AbortController().signal,
    );
    manager.start(startBody);
    sendChunk(manager, 0, false);

    fault(manager);
    expect(jobSignal?.aborted).toBe(true);
    manager.start({ ...startBody, streamId: "550e8400-e29b-41d4-a716-446655440071" });

    await waitUntil(() => emitted.length === 1);
    expect(emitted).toMatchObject([{ type: "voice.error", body: { streamId, message: "Voice transcription failed" } }]);
    job.resolve("late result");
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(emitted).toHaveLength(1);
  });

  it("normalizes seam tokens without rewriting original transcript spans", () => {
    expect(mergeCumulativeTranscript("Hello, WORLD!", "world... Again?")).toBe("Hello, WORLD! Again?");
    expect(mergeCumulativeTranscript("I can’t wait", "CAN'T WAIT for this.")).toBe("I can’t wait for this.");
    expect(mergeCumulativeTranscript("Straße CAFÉ", "STRASSE cafe\u0301 déjà")).toBe("Straße CAFÉ déjà");
    expect(mergeCumulativeTranscript("alpha beta alpha beta", "ALPHA, beta gamma")).toBe("alpha beta alpha beta gamma");
    expect(mergeCumulativeTranscript("cart", "art")).toBe("cart art");
  });

  it("auth downgrade aborts active fake Groq work without transcript output", async () => {
    let aborted = false;
    const emitted: OutboundMessage[] = [];
    const manager = new VoiceStreamManager(
      {
        submit: (_chunk, signal) => new Promise((_resolve, reject) => {
          signal.addEventListener("abort", () => {
            aborted = true;
            reject(new DOMException("Aborted", "AbortError"));
          }, { once: true });
        }),
      },
      (message) => { emitted.push(message); return Promise.resolve(); },
      new AbortController().signal,
    );
    manager.start(startBody);
    manager.audioPcm(encodeStreamPayload({ streamId, sequence: 0, offset: 0n, data: new Uint8Array(2) }));
    manager.boundary({ sessionId: streamId, chunkSequence: "0", final: false });

    await manager.cancelAll("AUTH_LOCK");
    expect(aborted).toBe(true);
    expect(emitted).toEqual([]);
  });
});
