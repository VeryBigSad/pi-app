import { VoiceError } from "./groq-client.js";

export interface Transcriber {
  transcribe(chunkId: string, pcm16le: Buffer, signal?: AbortSignal): Promise<string>;
}

interface Job {
  readonly chunkId: string;
  readonly pcm16le: Buffer;
  readonly resolve: (text: string) => void;
  readonly reject: (error: unknown) => void;
  readonly controller: AbortController;
}

export class TranscriptionQueue {
  private readonly transcriber: Transcriber;
  private readonly pending: Job[] = [];
  private active: Job | undefined;

  constructor(transcriber: Transcriber) {
    this.transcriber = transcriber;
  }

  submit(chunkId: string, pcm16le: Buffer): Promise<string> {
    if (this.pending.length >= 2) return Promise.reject(new VoiceError("VOICE_QUEUE_FULL", "voice queue is full"));
    return new Promise<string>((resolve, reject) => {
      this.pending.push({ chunkId, pcm16le: Buffer.from(pcm16le), resolve, reject, controller: new AbortController() });
      this.pump();
    });
  }

  cancelAll(): void {
    this.active?.controller.abort();
    for (const job of this.pending.splice(0)) {
      job.controller.abort();
      job.reject(new VoiceError("VOICE_CANCELED", "transcription canceled"));
    }
  }

  get size(): number {
    return this.pending.length + (this.active === undefined ? 0 : 1);
  }

  private pump(): void {
    if (this.active !== undefined) return;
    const job = this.pending.shift();
    if (job === undefined) return;
    this.active = job;
    void this.transcriber.transcribe(job.chunkId, job.pcm16le, job.controller.signal).then(job.resolve, job.reject).finally(() => {
      if (this.active === job) this.active = undefined;
      this.pump();
    });
  }
}
