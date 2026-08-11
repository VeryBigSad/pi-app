export interface AudioChunk {
  readonly sequence: number;
  readonly pcm16le: Buffer;
  readonly encodedSeconds: number;
  readonly final: boolean;
}

export interface VadOptions {
  readonly speechThreshold?: number;
  readonly preferredMs?: number;
  readonly forcedMs?: number;
  readonly preRollMs?: number;
  readonly overlapMs?: number;
  readonly preferredSilenceMs?: number;
}

const FRAME_MS = 20;
const SAMPLES_PER_FRAME = 320;

export class VadChunker {
  private readonly threshold: number;
  private readonly preferredFrames: number;
  private readonly forcedFrames: number;
  private readonly preRollFrames: number;
  private readonly overlapFrames: number;
  private readonly silenceBoundaryFrames: number;
  private readonly preRoll: Buffer[] = [];
  private active: Buffer[] | undefined;
  private activeFrames = 0;
  private silenceFrames = 0;
  private novelFrames = 0;
  private openUtterance = false;
  private sequence = 0;

  constructor(options: VadOptions = {}) {
    this.threshold = options.speechThreshold ?? 500;
    this.preferredFrames = frameCount(options.preferredMs ?? 8_000);
    this.forcedFrames = frameCount(options.forcedMs ?? 12_000);
    this.preRollFrames = frameCount(options.preRollMs ?? 300);
    this.overlapFrames = frameCount(options.overlapMs ?? 500);
    this.silenceBoundaryFrames = frameCount(options.preferredSilenceMs ?? 200);
    if (this.preferredFrames >= this.forcedFrames) throw new RangeError("preferred boundary must precede forced boundary");
  }

  push(frame: Int16Array): AudioChunk[] {
    if (frame.length !== SAMPLES_PER_FRAME) throw new TypeError("audio frame must contain 20 ms at 16 kHz");
    const bytes = Buffer.from(frame.buffer, frame.byteOffset, frame.byteLength);
    const speech = rms(frame) >= this.threshold;

    if (this.active === undefined) {
      if (!speech) {
        this.remember(bytes);
        return [];
      }
      this.active = [...this.preRoll.map((value) => Buffer.from(value)), Buffer.from(bytes)];
      this.preRoll.length = 0;
      this.activeFrames = this.active.length;
      this.silenceFrames = 0;
      this.novelFrames = this.active.length;
      return [];
    }

    this.active.push(Buffer.from(bytes));
    this.activeFrames += 1;
    this.novelFrames += 1;
    this.silenceFrames = speech ? 0 : this.silenceFrames + 1;

    if (this.activeFrames >= this.forcedFrames) return [this.emitForced()];
    if (this.activeFrames >= this.preferredFrames && this.silenceFrames >= this.silenceBoundaryFrames) {
      return [this.emitBoundary(false)];
    }
    return [];
  }

  finish(): AudioChunk[] {
    if (this.active !== undefined) {
      if (this.novelFrames > 0) return [this.emitBoundary(true)];
      this.reset();
      return [];
    }
    if (this.openUtterance && this.preRoll.length > 0) {
      const chunk = this.makeChunk(this.preRoll, true);
      this.reset();
      return [chunk];
    }
    this.reset();
    return [];
  }

  cancel(): void {
    this.reset();
  }

  private reset(): void {
    this.preRoll.length = 0;
    this.active = undefined;
    this.activeFrames = 0;
    this.silenceFrames = 0;
    this.novelFrames = 0;
    this.openUtterance = false;
  }

  private emitForced(): AudioChunk {
    const current = requireActive(this.active);
    const chunk = this.makeChunk(current, false);
    this.active = current.slice(-this.overlapFrames).map((value) => Buffer.from(value));
    this.activeFrames = this.active.length;
    this.silenceFrames = 0;
    this.novelFrames = 0;
    this.openUtterance = true;
    return chunk;
  }

  private emitBoundary(final: boolean): AudioChunk {
    const current = requireActive(this.active);
    const chunk = this.makeChunk(current, final);
    this.preRoll.length = 0;
    for (const value of current.slice(-this.preRollFrames)) this.remember(value);
    this.active = undefined;
    this.activeFrames = 0;
    this.silenceFrames = 0;
    this.novelFrames = 0;
    this.openUtterance = !final;
    return chunk;
  }

  private makeChunk(frames: Buffer[], final: boolean): AudioChunk {
    const pcm16le = Buffer.concat(frames);
    const chunk = {
      sequence: this.sequence,
      pcm16le,
      encodedSeconds: pcm16le.length / (16_000 * 2),
      final,
    };
    this.sequence += 1;
    return chunk;
  }

  private remember(frame: Buffer): void {
    this.preRoll.push(Buffer.from(frame));
    while (this.preRoll.length > this.preRollFrames) this.preRoll.shift();
  }
}

function frameCount(milliseconds: number): number {
  if (!Number.isSafeInteger(milliseconds) || milliseconds < FRAME_MS || milliseconds % FRAME_MS !== 0) {
    throw new TypeError("VAD duration must be a positive 20 ms multiple");
  }
  return milliseconds / FRAME_MS;
}

function rms(frame: Int16Array): number {
  let sum = 0;
  for (const sample of frame) sum += sample * sample;
  return Math.sqrt(sum / frame.length);
}

function requireActive(value: Buffer[] | undefined): Buffer[] {
  if (value === undefined) throw new Error("VAD state invariant failed");
  return value;
}
