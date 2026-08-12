import { MAX_BINARY_DATA_BYTES, MAX_UINT64, MAX_VOICE_CHUNK_BYTES } from "./constants.js";
import { parseUint64 } from "./binary.js";
import { ProtocolError } from "./errors.js";

export interface VoicePcmChunk {
  readonly sequence: bigint;
  readonly final: boolean;
  readonly pcm16le: Uint8Array;
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;

export class VoicePcmStream {
  private nextFrameSequence = 0;
  private nextOffset = 0n;
  private nextChunkSequence = 0n;
  private readonly parts: Uint8Array[] = [];
  private chunkBytes = 0;
  private ended = false;

  constructor(readonly streamId: string) {
    if (!UUID_V4.test(streamId)) this.fail();
  }

  accept(streamId: string, sequence: number, offset: bigint, data: Uint8Array): void {
    if (
      this.ended || streamId !== this.streamId || sequence !== this.nextFrameSequence || offset !== this.nextOffset ||
      !Number.isInteger(sequence) || sequence < 0 || sequence > 0xffff_ffff || data.length === 0 ||
      data.length > MAX_BINARY_DATA_BYTES || data.length % 2 !== 0 ||
      this.chunkBytes + data.length > MAX_VOICE_CHUNK_BYTES || this.nextOffset > MAX_UINT64 - BigInt(data.length)
    ) this.fail();
    this.parts.push(data.slice());
    this.chunkBytes += data.length;
    this.nextFrameSequence += 1;
    this.nextOffset += BigInt(data.length);
  }

  boundary(streamId: string, chunkSequence: string, final: boolean): VoicePcmChunk {
    let sequence: bigint;
    try {
      sequence = parseUint64(chunkSequence);
    } catch {
      return this.fail();
    }
    if (
      this.ended || streamId !== this.streamId || sequence !== this.nextChunkSequence || typeof final !== "boolean" ||
      this.chunkBytes === 0 && !final || sequence === MAX_UINT64 && !final
    ) this.fail();
    const pcm16le = concat(this.parts, this.chunkBytes);
    this.parts.length = 0;
    this.chunkBytes = 0;
    if (final) this.ended = true;
    else this.nextChunkSequence += 1n;
    return { sequence, final, pcm16le };
  }

  cancel(): void {
    this.ended = true;
    this.parts.length = 0;
    this.chunkBytes = 0;
  }

  get offset(): bigint {
    return this.nextOffset;
  }

  private fail(): never {
    this.cancel();
    throw new ProtocolError("STREAM_INVALID", "Voice PCM ordering, format, or bounds are invalid");
  }
}

function concat(parts: readonly Uint8Array[], length: number): Uint8Array {
  const output = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    output.set(part, offset);
    offset += part.length;
  }
  return output;
}
