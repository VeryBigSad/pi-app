import { describe, expect, it } from "vitest";
import { GatewayVoiceRuntime } from "../src/daemon/voice-service.js";
import type { VoiceService } from "../src/daemon/voice-service.js";
import type { VoiceAudioChunk, VoiceTranscriptSink } from "../src/gateway/types.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440070";

interface Emitted {
  readonly kind: "partial" | "finish";
  readonly sessionId: string;
  readonly chunkSequence: number;
  readonly revision?: number;
  readonly text: string;
}

function chunk(final: boolean): VoiceAudioChunk {
  return { sessionId, chunkSequence: 4, final, pcm16le: new Uint8Array(320) };
}

function sink(emitted: Emitted[]): VoiceTranscriptSink {
  return {
    partial: (update) => {
      emitted.push({ kind: "partial", ...update });
      return Promise.resolve();
    },
    finish: (update) => {
      emitted.push({ kind: "finish", ...update });
      return Promise.resolve();
    },
  };
}

describe("gateway voice runtime", () => {
  it("emits Groq results as revision-1 partial and closes final chunks with finish", async () => {
    const transcribed: { chunkId: string; bytes: number }[] = [];
    const voice = {
      transcribe: (chunkId: string, pcm: Buffer) => {
        transcribed.push({ chunkId, bytes: pcm.byteLength });
        return Promise.resolve("hello world");
      },
    } as unknown as VoiceService;
    const runtime = new GatewayVoiceRuntime(voice);

    const emitted: Emitted[] = [];
    await runtime.submit(chunk(true), sink(emitted), new AbortController().signal);
    expect(transcribed).toEqual([{ chunkId: `${sessionId}:4`, bytes: 320 }]);
    expect(emitted).toEqual([
      { kind: "partial", sessionId, chunkSequence: 4, revision: 1, text: "hello world" },
      { kind: "finish", sessionId, chunkSequence: 4, text: "hello world" },
    ]);
  });

  it("emits only a partial for non-final chunks", async () => {
    const voice = { transcribe: () => Promise.resolve("partial text") } as unknown as VoiceService;
    const runtime = new GatewayVoiceRuntime(voice);
    const emitted: Emitted[] = [];
    await runtime.submit(chunk(false), sink(emitted), new AbortController().signal);
    expect(emitted).toEqual([{ kind: "partial", sessionId, chunkSequence: 4, revision: 1, text: "partial text" }]);
  });

  it("propagates transcription failures without emitting transcripts", async () => {
    const voice = { transcribe: () => Promise.reject(new Error("VOICE_QUOTA")) } as unknown as VoiceService;
    const runtime = new GatewayVoiceRuntime(voice);
    const emitted: Emitted[] = [];
    await expect(runtime.submit(chunk(true), sink(emitted), new AbortController().signal)).rejects.toThrow(/VOICE_QUOTA/);
    expect(emitted).toEqual([]);
  });
});
