import type { VoiceAudioChunk, VoiceRuntime, VoiceTranscriptSink } from "../gateway/types.js";
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

  transcribe(chunkId: string, pcm16le: Buffer): Promise<string> {
    return this.queue.submit(chunkId, pcm16le);
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

/**
 * Bridges gateway voice.audio chunks into the Groq pipeline: every transcription
 * result is emitted as voice.partial (revision 1 for the chunk) and, when the
 * chunk is marked final, voice.finish closes it.
 */
export class GatewayVoiceRuntime implements VoiceRuntime {
  constructor(private readonly voice: VoiceService) {}

  async submit(chunk: VoiceAudioChunk, sink: VoiceTranscriptSink, signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    const text = await this.voice.transcribe(`${chunk.sessionId}:${String(chunk.chunkSequence)}`, Buffer.from(chunk.pcm16le));
    signal.throwIfAborted();
    await sink.partial({ sessionId: chunk.sessionId, chunkSequence: chunk.chunkSequence, revision: 1, text }, signal);
    if (chunk.final) {
      await sink.finish({ sessionId: chunk.sessionId, chunkSequence: chunk.chunkSequence, text }, signal);
    }
  }
}

export { VoiceError };
