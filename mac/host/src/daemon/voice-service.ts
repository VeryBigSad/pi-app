import type { VoiceAudioChunk, VoiceRuntime } from "../gateway/types.js";
import { GroqTranscriber, VoiceError } from "../voice/groq-client.js";
import { VoiceRateLedger } from "../voice/rate-ledger.js";
import { TranscriptionQueue } from "../voice/transcription-queue.js";

/**
 * Production voice stack: Groq transcriber (reads ~/.groq_key at request time,
 * enforcing mode 0600) behind the durable rate ledger and bounded queue.
 */
export class VoiceService {
  private readonly ledger: VoiceRateLedger;
  private readonly queue: TranscriptionQueue;

  constructor(ledgerPath: string, options: { readonly keyPath?: string; readonly fetch?: typeof fetch } = {}) {
    this.ledger = new VoiceRateLedger(ledgerPath);
    const transcriber = new GroqTranscriber({
      ledger: this.ledger,
      ...(options.keyPath === undefined ? {} : { keyPath: options.keyPath }),
      ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
    });
    this.queue = new TranscriptionQueue(transcriber);
  }

  transcribe(chunkId: string, pcm16le: Buffer, signal?: AbortSignal): Promise<string> {
    return this.queue.submit(chunkId, pcm16le, signal);
  }

  status(): { queueSize: number; limits: { requestsPerDay: number; audioSecondsPerDay: number } } {
    return {
      queueSize: this.queue.size,
      limits: {
        requestsPerDay: this.ledger.limits.requestsPerDay,
        audioSecondsPerDay: this.ledger.limits.audioSecondsPerDay,
      },
    };
  }

  close(): void {
    this.queue.cancelAll();
    this.ledger.close();
  }
}

export class GatewayVoiceRuntime implements VoiceRuntime {
  constructor(private readonly voice: VoiceService) {}

  async submit(chunk: VoiceAudioChunk, signal: AbortSignal): Promise<string> {
    signal.throwIfAborted();
    const text = await this.voice.transcribe(`${chunk.sessionId}:${chunk.chunkSequence.toString()}`, Buffer.from(chunk.pcm16le), signal);
    signal.throwIfAborted();
    return text;
  }
}

export { VoiceError };
