import { describe, expect, it } from "vitest";
import { GatewayVoiceRuntime } from "../src/daemon/voice-service.js";
import type { VoiceService } from "../src/daemon/voice-service.js";
import type { VoiceAudioChunk } from "../src/gateway/types.js";

const sessionId = "550e8400-e29b-41d4-a716-446655440070";

function chunk(final: boolean): VoiceAudioChunk {
  return { sessionId, chunkSequence: 4n, final, pcm16le: new Uint8Array(320) };
}

describe("gateway voice runtime", () => {
  it("passes bounded PCM through the existing Groq service with cancellation", async () => {
    const transcribed: { chunkId: string; bytes: number; signal: AbortSignal | undefined }[] = [];
    const voice = {
      transcribe: (chunkId: string, pcm: Buffer, signal?: AbortSignal) => {
        transcribed.push({ chunkId, bytes: pcm.byteLength, signal });
        return Promise.resolve("hello world");
      },
    } as unknown as VoiceService;
    const runtime = new GatewayVoiceRuntime(voice);
    const controller = new AbortController();

    await expect(runtime.submit(chunk(true), controller.signal)).resolves.toBe("hello world");
    expect(transcribed).toEqual([{ chunkId: `${sessionId}:4`, bytes: 320, signal: controller.signal }]);
  });

  it("propagates transcription failures", async () => {
    const voice = { transcribe: () => Promise.reject(new Error("VOICE_QUOTA")) } as unknown as VoiceService;
    const runtime = new GatewayVoiceRuntime(voice);
    await expect(runtime.submit(chunk(true), new AbortController().signal)).rejects.toThrow(/VOICE_QUOTA/u);
  });
});
