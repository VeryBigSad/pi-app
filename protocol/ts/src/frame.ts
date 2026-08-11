import { MAX_BINARY_DATA_BYTES, MAX_FRAME_PAYLOAD_BYTES, MAX_JSON_PAYLOAD_BYTES, PIMB_HEADER_BYTES, PIMB_MAGIC, PROTOCOL_MAJOR, FrameKind, STREAM_PREFIX_BYTES, TERMINAL_PREFIX_BYTES } from "./constants.js";
import { bytesToUuid, readUint64BigEndian, uuidToBytes, writeUint64BigEndian } from "./binary.js";
import { ProtocolError } from "./errors.js";
import { assertJsonValue, decodeUtf8Strict, parseJsonObject } from "./json.js";

export interface Frame {
  readonly kind: FrameKind;
  readonly payload: Uint8Array;
}

export interface StreamPayload {
  readonly streamId: string;
  readonly sequence: number;
  readonly offset: bigint;
  readonly data: Uint8Array;
}

export interface TerminalPayload {
  readonly terminalGeneration: bigint;
  readonly sequence: bigint;
  readonly data: Uint8Array;
}

export function encodeFrame(kind: FrameKind, payload: Uint8Array): Uint8Array {
  assertPayload(kind, payload);
  const frame = new Uint8Array(PIMB_HEADER_BYTES + payload.length);
  frame.set(PIMB_MAGIC, 0);
  frame[4] = PROTOCOL_MAJOR;
  frame[5] = kind;
  new DataView(frame.buffer).setUint32(8, payload.length, false);
  frame.set(payload, PIMB_HEADER_BYTES);
  return frame;
}

export function decodeFrame(frame: Uint8Array): Frame {
  if (frame.length < PIMB_HEADER_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Truncated PIMB header");
  }
  const header = parseHeader(frame.subarray(0, PIMB_HEADER_BYTES));
  if (frame.length !== PIMB_HEADER_BYTES + header.length) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB frame length does not match its header");
  }
  const payload = frame.slice(PIMB_HEADER_BYTES);
  assertPayload(header.kind, payload);
  return { kind: header.kind, payload };
}

export class FrameDecoder {
  private readonly headerBytes = new Uint8Array(PIMB_HEADER_BYTES);
  private headerCount = 0;
  private header: { kind: FrameKind; length: number } | undefined;
  private payload: Uint8Array | undefined;
  private payloadCount = 0;

  push(chunk: Uint8Array): Frame[] {
    const frames: Frame[] = [];
    let offset = 0;
    while (offset < chunk.length) {
      if (this.header === undefined) {
        const count = Math.min(PIMB_HEADER_BYTES - this.headerCount, chunk.length - offset);
        this.headerBytes.set(chunk.subarray(offset, offset + count), this.headerCount);
        this.headerCount += count;
        offset += count;
        if (this.headerCount < PIMB_HEADER_BYTES) break;
        this.header = parseHeader(this.headerBytes);
        this.payload = new Uint8Array(this.header.length);
      }

      const count = Math.min(this.header.length - this.payloadCount, chunk.length - offset);
      this.payload!.set(chunk.subarray(offset, offset + count), this.payloadCount);
      this.payloadCount += count;
      offset += count;
      if (this.payloadCount !== this.header.length) continue;

      assertPayload(this.header.kind, this.payload!);
      frames.push({ kind: this.header.kind, payload: this.payload! });
      this.headerCount = 0;
      this.header = undefined;
      this.payload = undefined;
      this.payloadCount = 0;
    }
    return frames;
  }

  finish(): void {
    if (this.headerCount !== 0 || this.header !== undefined) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "Truncated PIMB frame");
    }
  }

  bufferedBytes(): number {
    return this.headerCount + this.payloadCount;
  }
}

export function encodeJsonPayload(value: object): Uint8Array {
  assertJsonValue(value);
  const encoded = new TextEncoder().encode(JSON.stringify(value));
  if (encoded.length > MAX_JSON_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "JSON payload exceeds its bound");
  }
  return encoded;
}

export function decodeJsonPayload(payload: Uint8Array) {
  if (payload.length > MAX_JSON_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "JSON payload exceeds its bound");
  }
  return parseJsonObject(decodeUtf8Strict(payload));
}

export function encodeStreamPayload(value: StreamPayload): Uint8Array {
  if (!Number.isInteger(value.sequence) || value.sequence < 0 || value.sequence > 0xffff_ffff || value.offset < 0n || value.data.length > MAX_BINARY_DATA_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Stream prefix is invalid");
  }
  const payload = new Uint8Array(STREAM_PREFIX_BYTES + value.data.length);
  payload.set(uuidToBytes(value.streamId));
  new DataView(payload.buffer).setUint32(16, value.sequence, false);
  payload.set(writeUint64BigEndian(value.offset), 20);
  payload.set(value.data, STREAM_PREFIX_BYTES);
  return payload;
}

export function decodeStreamPayload(payload: Uint8Array): StreamPayload {
  if (payload.length < STREAM_PREFIX_BYTES || payload.length - STREAM_PREFIX_BYTES > MAX_BINARY_DATA_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Stream payload is out of bounds");
  }
  return { streamId: bytesToUuid(payload.subarray(0, 16)), sequence: new DataView(payload.buffer, payload.byteOffset, payload.byteLength).getUint32(16, false), offset: readUint64BigEndian(payload, 20), data: payload.slice(STREAM_PREFIX_BYTES) };
}

export function encodeTerminalPayload(value: TerminalPayload): Uint8Array {
  if (value.terminalGeneration < 0n || value.sequence < 0n || value.data.length > MAX_BINARY_DATA_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Terminal prefix is invalid");
  }
  const payload = new Uint8Array(TERMINAL_PREFIX_BYTES + value.data.length);
  payload.set(writeUint64BigEndian(value.terminalGeneration));
  payload.set(writeUint64BigEndian(value.sequence), 8);
  payload.set(value.data, TERMINAL_PREFIX_BYTES);
  return payload;
}

export function decodeTerminalPayload(payload: Uint8Array): TerminalPayload {
  if (payload.length < TERMINAL_PREFIX_BYTES || payload.length - TERMINAL_PREFIX_BYTES > MAX_BINARY_DATA_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Terminal payload is out of bounds");
  }
  return { terminalGeneration: readUint64BigEndian(payload), sequence: readUint64BigEndian(payload, 8), data: payload.slice(TERMINAL_PREFIX_BYTES) };
}

function parseHeader(header: Uint8Array): { kind: FrameKind; length: number } {
  if (!PIMB_MAGIC.every((value, index) => header[index] === value)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB magic is invalid");
  }
  if (header[4] !== PROTOCOL_MAJOR) {
    throw new ProtocolError("UNSUPPORTED_VERSION", "PIMB major version is unsupported");
  }
  const kind = header[5];
  if (kind !== FrameKind.Json && kind !== FrameKind.BlobChunk && kind !== FrameKind.AudioPcm && kind !== FrameKind.TerminalBytes) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB frame kind is invalid");
  }
  const view = new DataView(header.buffer, header.byteOffset, header.byteLength);
  if (view.getUint16(6, false) !== 0) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB flags are invalid");
  }
  const length = view.getUint32(8, false);
  if (length > MAX_FRAME_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "PIMB payload exceeds its bound");
  }
  if (kind === FrameKind.Json && length > MAX_JSON_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "JSON payload exceeds its bound");
  }
  if ((kind === FrameKind.BlobChunk || kind === FrameKind.AudioPcm) && (length < STREAM_PREFIX_BYTES || length - STREAM_PREFIX_BYTES > MAX_BINARY_DATA_BYTES)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Stream payload length is out of bounds");
  }
  if (kind === FrameKind.TerminalBytes && (length < TERMINAL_PREFIX_BYTES || length - TERMINAL_PREFIX_BYTES > MAX_BINARY_DATA_BYTES)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Terminal payload length is out of bounds");
  }
  return { kind, length };
}

function assertPayload(kind: FrameKind, payload: Uint8Array): void {
  if (payload.length > MAX_FRAME_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "PIMB payload exceeds its bound");
  }
  if (kind === FrameKind.Json && payload.length > MAX_JSON_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "JSON payload exceeds its bound");
  }
  if ((kind === FrameKind.BlobChunk || kind === FrameKind.AudioPcm) && (payload.length < STREAM_PREFIX_BYTES || payload.length - STREAM_PREFIX_BYTES > MAX_BINARY_DATA_BYTES)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Stream payload is out of bounds");
  }
  if (kind === FrameKind.TerminalBytes && (payload.length < TERMINAL_PREFIX_BYTES || payload.length - TERMINAL_PREFIX_BYTES > MAX_BINARY_DATA_BYTES)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Terminal payload is out of bounds");
  }
}
