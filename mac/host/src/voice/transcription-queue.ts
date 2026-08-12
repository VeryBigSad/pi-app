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
  readonly externalSignal: AbortSignal | undefined;
  readonly externalAbort: (() => void) | undefined;
  settled: boolean;
}

export class TranscriptionQueue {
  private readonly transcriber: Transcriber;
  private readonly pending: Job[] = [];
  private active: Job | undefined;

  constructor(transcriber: Transcriber) {
    this.transcriber = transcriber;
  }

  submit(chunkId: string, pcm16le: Buffer, signal?: AbortSignal): Promise<string> {
    if (signal?.aborted === true) return Promise.reject(new VoiceError("VOICE_CANCELED", "transcription canceled"));
    if (this.pending.length >= 2) return Promise.reject(new VoiceError("VOICE_QUEUE_FULL", "voice queue is full"));
    return new Promise<string>((resolve, reject) => {
      const controller = new AbortController();
      const holder: { job?: Job } = {};
      const externalAbort = signal === undefined ? undefined : () => {
        if (holder.job !== undefined) this.abort(holder.job);
      };
      const job: Job = {
        chunkId,
        pcm16le: Buffer.from(pcm16le),
        resolve,
        reject,
        controller,
        externalSignal: signal,
        externalAbort,
        settled: false,
      };
      holder.job = job;
      if (signal !== undefined && externalAbort !== undefined) signal.addEventListener("abort", externalAbort, { once: true });
      this.pending.push(job);
      this.pump();
    });
  }

  cancelAll(): void {
    const active = this.active;
    if (active !== undefined) {
      active.controller.abort();
      this.reject(active, new VoiceError("VOICE_CANCELED", "transcription canceled"));
    }
    for (const job of this.pending.splice(0)) this.reject(job, new VoiceError("VOICE_CANCELED", "transcription canceled"));
  }

  get size(): number {
    return this.pending.length + (this.active === undefined ? 0 : 1);
  }

  private abort(job: Job): void {
    if (job.settled) return;
    if (this.active === job) {
      job.controller.abort();
      this.reject(job, new VoiceError("VOICE_CANCELED", "transcription canceled"));
      return;
    }
    const index = this.pending.indexOf(job);
    if (index >= 0) this.pending.splice(index, 1);
    this.reject(job, new VoiceError("VOICE_CANCELED", "transcription canceled"));
  }

  private pump(): void {
    if (this.active !== undefined) return;
    const job = this.pending.shift();
    if (job === undefined) return;
    this.active = job;
    void this.transcriber.transcribe(job.chunkId, job.pcm16le, job.controller.signal).then(
      (text) => this.resolve(job, text),
      (error: unknown) => this.reject(job, error),
    ).finally(() => {
      if (this.active === job) this.active = undefined;
      this.pump();
    });
  }

  private resolve(job: Job, text: string): void {
    if (job.settled) return;
    job.settled = true;
    this.detach(job);
    job.resolve(text);
  }

  private reject(job: Job, error: unknown): void {
    if (job.settled) return;
    job.settled = true;
    this.detach(job);
    job.reject(error);
  }

  private detach(job: Job): void {
    if (job.externalAbort !== undefined) job.externalSignal?.removeEventListener("abort", job.externalAbort);
  }
}
