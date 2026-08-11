import {
  FrameKind,
  MAX_BINARY_DATA_BYTES,
  MAX_FRAME_PAYLOAD_BYTES,
  MAX_JSON_PAYLOAD_BYTES,
  PIMB_HEADER_BYTES,
  PIMB_MAGIC,
  PROTOCOL_MAJOR,
  STREAM_PREFIX_BYTES,
  TERMINAL_PREFIX_BYTES,
  encodeFrame,
  type Frame,
} from "@pimobile/protocol";
import { ProtocolError } from "@pimobile/protocol";
import { deferredVoid } from "./deferred.js";
import type { ByteTransport, GatewayClock } from "./types.js";

export interface FrameWriterLimits {
  readonly frames: number;
  readonly bytes: number;
  readonly stallMs: number;
}

export class BoundedFrameReader {
  private readonly header = new Uint8Array(PIMB_HEADER_BYTES);
  private headerLength = 0;
  private payload: Uint8Array | undefined;
  private payloadLength = 0;
  private kind: FrameKind | undefined;
  private ended = false;

  constructor(private readonly transport: ByteTransport) {}

  async next(signal: AbortSignal): Promise<Frame | null> {
    if (this.ended) return null;
    for (;;) {
      signal.throwIfAborted();
      const complete = this.takeComplete();
      if (complete !== undefined) return complete;
      const maximumRead = MAX_FRAME_PAYLOAD_BYTES + PIMB_HEADER_BYTES;
      const chunk = await this.transport.read(maximumRead, signal);
      if (chunk === null) {
        this.ended = true;
        if (this.headerLength !== 0 || this.payload !== undefined) {
          throw new ProtocolError("PROTOCOL_VIOLATION", "Truncated PIMB frame");
        }
        return null;
      }
      if (chunk.length > maximumRead) throw new ProtocolError("RESOURCE_EXHAUSTED", "Transport exceeded bounded read request");
      this.consume(chunk);
    }
  }

  private readonly ready: Frame[] = [];

  private consume(chunk: Uint8Array): void {
    let offset = 0;
    while (offset < chunk.length) {
      if (this.payload === undefined) {
        const count = Math.min(PIMB_HEADER_BYTES - this.headerLength, chunk.length - offset);
        this.header.set(chunk.subarray(offset, offset + count), this.headerLength);
        this.headerLength += count;
        offset += count;
        if (this.headerLength === PIMB_HEADER_BYTES) this.beginPayload();
        continue;
      }
      const count = Math.min(this.payload.length - this.payloadLength, chunk.length - offset);
      this.payload.set(chunk.subarray(offset, offset + count), this.payloadLength);
      this.payloadLength += count;
      offset += count;
      if (this.payloadLength === this.payload.length) this.finishPayload();
    }
  }

  private beginPayload(): void {
    if (!PIMB_MAGIC.every((value, index) => this.header[index] === value)) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB magic is invalid");
    }
    if (this.header[4] !== PROTOCOL_MAJOR) {
      throw new ProtocolError("UNSUPPORTED_VERSION", "PIMB major version is unsupported");
    }
    const kind = this.header[5];
    if (kind !== FrameKind.Json && kind !== FrameKind.BlobChunk && kind !== FrameKind.AudioPcm && kind !== FrameKind.TerminalBytes) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB frame kind is invalid");
    }
    const view = new DataView(this.header.buffer, this.header.byteOffset, this.header.byteLength);
    if (view.getUint16(6, false) !== 0) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "PIMB flags are invalid");
    }
    const length = view.getUint32(8, false);
    assertDeclaredLength(kind, length);
    this.kind = kind;
    this.payload = new Uint8Array(length);
    this.payloadLength = 0;
    if (length === 0) this.finishPayload();
  }

  private finishPayload(): void {
    const kind = this.kind;
    const payload = this.payload;
    if (kind === undefined || payload === undefined) throw new Error("frame reader invariant");
    assertCompletedLength(kind, payload.length);
    if (this.ready.length >= 512) throw new ProtocolError("RESOURCE_EXHAUSTED", "Inbound frame queue is full");
    this.ready.push({ kind, payload });
    this.headerLength = 0;
    this.payload = undefined;
    this.payloadLength = 0;
    this.kind = undefined;
  }

  private takeComplete(): Frame | undefined {
    return this.ready.shift();
  }
}

interface PendingFrame {
  readonly bytes: Uint8Array;
  readonly signal: AbortSignal;
  readonly resolve: () => void;
  readonly reject: (reason: unknown) => void;
}

export class BoundedFrameWriter {
  private readonly queue: PendingFrame[] = [];
  private readonly capacityWaiters = new Set<() => void>();
  private queuedBytes = 0;
  private pumping = false;
  private failure: Error | undefined;

  constructor(
    private readonly transport: ByteTransport,
    private readonly clock: GatewayClock,
    private readonly limits: FrameWriterLimits,
  ) {
    if (!Number.isSafeInteger(limits.frames) || limits.frames < 1 || limits.frames > 512) throw new RangeError("outbound frame limit is invalid");
    if (!Number.isSafeInteger(limits.bytes) || limits.bytes < PIMB_HEADER_BYTES || limits.bytes > 8 * 1024 * 1024) throw new RangeError("outbound byte limit is invalid");
    if (!Number.isSafeInteger(limits.stallMs) || limits.stallMs < 1 || limits.stallMs > 10_000) throw new RangeError("outbound stall limit is invalid");
  }

  async send(kind: FrameKind, payload: Uint8Array, signal: AbortSignal): Promise<void> {
    const bytes = encodeFrame(kind, payload);
    if (bytes.length > this.limits.bytes) {
      throw new ProtocolError("RESOURCE_EXHAUSTED", "Frame cannot fit outbound queue");
    }
    while (!this.hasCapacity(bytes.length)) {
      await this.waitForCapacity(signal);
    }
    signal.throwIfAborted();
    if (this.failure !== undefined) throw this.failure;
    const completion = deferredVoid();
    this.queue.push({ bytes, signal, resolve: completion.resolve, reject: completion.reject });
    this.queuedBytes += bytes.length;
    this.startPump();
    await completion.promise;
  }

  abort(reason: unknown): void {
    if (this.failure !== undefined) return;
    const failure = reason instanceof Error ? reason : new Error("Frame writer aborted");
    this.failure = failure;
    for (const pending of this.queue.splice(0)) pending.reject(failure);
    this.queuedBytes = 0;
    this.notifyCapacity();
  }

  private hasCapacity(size: number): boolean {
    if (this.failure !== undefined) throw this.failure;
    return this.queue.length < this.limits.frames && this.queuedBytes + size <= this.limits.bytes;
  }

  private async waitForCapacity(signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    const waiter = deferredVoid();
    const wake = (): void => waiter.resolve();
    this.capacityWaiters.add(wake);
    const timeout = this.clock.setTimeout(() => waiter.reject(new ProtocolError("RESOURCE_EXHAUSTED", "Outbound queue remained full")), this.limits.stallMs);
    const abort = (): void => waiter.reject(signal.reason);
    signal.addEventListener("abort", abort, { once: true });
    try {
      await waiter.promise;
    } finally {
      this.capacityWaiters.delete(wake);
      this.clock.clearTimeout(timeout);
      signal.removeEventListener("abort", abort);
    }
  }

  private startPump(): void {
    if (this.pumping) return;
    this.pumping = true;
    void this.pump();
  }

  private async pump(): Promise<void> {
    try {
      while (this.queue.length > 0) {
        const pending = this.queue[0];
        if (pending === undefined) break;
        await this.transport.write(pending.bytes, pending.signal);
        this.queue.shift();
        this.queuedBytes -= pending.bytes.length;
        pending.resolve();
        this.notifyCapacity();
      }
    } catch (error) {
      this.abort(error);
    } finally {
      this.pumping = false;
      if (this.queue.length > 0 && this.failure === undefined) this.startPump();
    }
  }

  private notifyCapacity(): void {
    for (const wake of this.capacityWaiters) wake();
    this.capacityWaiters.clear();
  }
}

function assertDeclaredLength(kind: FrameKind, length: number): void {
  if (length > MAX_FRAME_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "PIMB payload exceeds its bound");
  }
  if (kind === FrameKind.Json && length > MAX_JSON_PAYLOAD_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "JSON payload exceeds its bound");
  }
  if ((kind === FrameKind.BlobChunk || kind === FrameKind.AudioPcm) && length > STREAM_PREFIX_BYTES + MAX_BINARY_DATA_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "Stream payload exceeds its bound");
  }
  if (kind === FrameKind.TerminalBytes && length > TERMINAL_PREFIX_BYTES + MAX_BINARY_DATA_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "Terminal payload exceeds its bound");
  }
}

function assertCompletedLength(kind: FrameKind, length: number): void {
  if ((kind === FrameKind.BlobChunk || kind === FrameKind.AudioPcm) && length < STREAM_PREFIX_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Stream payload is truncated");
  }
  if (kind === FrameKind.TerminalBytes && length < TERMINAL_PREFIX_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Terminal payload is truncated");
  }
}
