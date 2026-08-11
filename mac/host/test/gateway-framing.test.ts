import { FrameKind, MAX_FRAME_PAYLOAD_BYTES, PIMB_HEADER_BYTES, PROTOCOL_MAJOR, encodeFrame } from "@pimobile/protocol";
import { describe, expect, it } from "vitest";
import { BoundedFrameReader, BoundedFrameWriter } from "../src/gateway/framing.js";
import type { ByteTransport, GatewayClock } from "../src/gateway/types.js";

class TestClock implements GatewayClock {
  nowMs = 0;
  private nextId = 0;
  private readonly timers = new Map<number, { at: number; operation: () => void }>();

  now(): number {
    return this.nowMs;
  }

  setTimeout(operation: () => void, delayMs: number): unknown {
    const id = ++this.nextId;
    this.timers.set(id, { at: this.nowMs + delayMs, operation });
    return id;
  }

  clearTimeout(handle: unknown): void {
    if (typeof handle === "number") this.timers.delete(handle);
  }

  advance(milliseconds: number): void {
    this.nowMs += milliseconds;
    for (const [id, timer] of [...this.timers]) {
      if (timer.at <= this.nowMs) {
        this.timers.delete(id);
        timer.operation();
      }
    }
  }
}

class ChunkTransport implements ByteTransport {
  readonly writes: Uint8Array[] = [];
  readonly closes: string[] = [];

  constructor(readonly chunks: (Uint8Array | null)[]) {}

  read(): Promise<Uint8Array | null> {
    return Promise.resolve(this.chunks.shift() ?? null);
  }

  write(bytes: Uint8Array): Promise<void> {
    this.writes.push(bytes.slice());
    return Promise.resolve();
  }

  close(code: string): Promise<void> {
    this.closes.push(code);
    return Promise.resolve();
  }
}

class BlockedWriteTransport extends ChunkTransport {
  private readonly releases: (() => void)[] = [];

  constructor() {
    super([]);
  }

  override async write(bytes: Uint8Array): Promise<void> {
    this.writes.push(bytes.slice());
    await new Promise<void>((resolve) => this.releases.push(resolve));
  }

  release(): void {
    this.releases.shift()?.();
  }
}

function header(kind: FrameKind, length: number, flags = 0, major = PROTOCOL_MAJOR): Uint8Array {
  const bytes = new Uint8Array(PIMB_HEADER_BYTES);
  bytes.set([0x50, 0x49, 0x4d, 0x42]);
  bytes[4] = major;
  bytes[5] = kind;
  const view = new DataView(bytes.buffer);
  view.setUint16(6, flags, false);
  view.setUint32(8, length, false);
  return bytes;
}

async function settle(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

describe("bounded gateway PIMB framing", () => {
  it("decodes fragmented and coalesced frames without boundary assumptions", async () => {
    const first = encodeFrame(FrameKind.Json, new TextEncoder().encode("{}"));
    const second = encodeFrame(FrameKind.TerminalBytes, new Uint8Array(16));
    const all = new Uint8Array(first.length + second.length);
    all.set(first);
    all.set(second, first.length);
    const transport = new ChunkTransport([
      all.slice(0, 1),
      all.slice(1, 11),
      all.slice(11, first.length + 7),
      all.slice(first.length + 7),
      null,
    ]);
    const reader = new BoundedFrameReader(transport);
    const signal = new AbortController().signal;

    await expect(reader.next(signal)).resolves.toEqual({ kind: FrameKind.Json, payload: new TextEncoder().encode("{}") });
    await expect(reader.next(signal)).resolves.toEqual({ kind: FrameKind.TerminalBytes, payload: new Uint8Array(16) });
    await expect(reader.next(signal)).resolves.toBeNull();
  });

  it.each([
    ["magic", (() => { const value = header(FrameKind.Json, 0); value[0] = 0; return value; })(), "PROTOCOL_VIOLATION"],
    ["major", header(FrameKind.Json, 0, 0, 2), "UNSUPPORTED_VERSION"],
    ["flags", header(FrameKind.Json, 0, 1), "PROTOCOL_VIOLATION"],
    ["declared bound", header(FrameKind.Json, 256 * 1024 + 1), "FRAME_TOO_LARGE"],
    ["absolute bound", header(FrameKind.BlobChunk, MAX_FRAME_PAYLOAD_BYTES + 1), "FRAME_TOO_LARGE"],
  ])("rejects invalid %s before payload allocation", async (_name, bytes, code) => {
    const reader = new BoundedFrameReader(new ChunkTransport([bytes, null]));
    await expect(reader.next(new AbortController().signal)).rejects.toMatchObject({ code });
  });

  it("rejects truncated and transport-overrun input", async () => {
    const truncated = new BoundedFrameReader(new ChunkTransport([header(FrameKind.Json, 2), Uint8Array.of(0x7b), null]));
    await expect(truncated.next(new AbortController().signal)).rejects.toMatchObject({ code: "PROTOCOL_VIOLATION" });

    const overrun = new BoundedFrameReader(new ChunkTransport([new Uint8Array(MAX_FRAME_PAYLOAD_BYTES + PIMB_HEADER_BYTES + 1)]));
    await expect(overrun.next(new AbortController().signal)).rejects.toMatchObject({ code: "RESOURCE_EXHAUSTED" });
  });

  it("applies outbound flow control and resumes without dropping frames", async () => {
    const transport = new BlockedWriteTransport();
    const clock = new TestClock();
    const writer = new BoundedFrameWriter(transport, clock, { frames: 1, bytes: 64, stallMs: 10 });
    const signal = new AbortController().signal;
    const first = writer.send(FrameKind.Json, new TextEncoder().encode("{}"), signal);
    await settle();
    const second = writer.send(FrameKind.Json, new TextEncoder().encode("[]"), signal);
    await settle();
    expect(transport.writes).toHaveLength(1);

    transport.release();
    await first;
    await settle();
    expect(transport.writes).toHaveLength(2);
    transport.release();
    await expect(second).resolves.toBeUndefined();
  });

  it("fails a stalled queue and rejects every queued write on cancellation", async () => {
    const transport = new BlockedWriteTransport();
    const clock = new TestClock();
    const writer = new BoundedFrameWriter(transport, clock, { frames: 1, bytes: 64, stallMs: 10 });
    const signal = new AbortController().signal;
    const first = writer.send(FrameKind.Json, new TextEncoder().encode("{}"), signal);
    await settle();
    const stalled = writer.send(FrameKind.Json, new TextEncoder().encode("[]"), signal);
    await settle();
    clock.advance(10);
    await expect(stalled).rejects.toMatchObject({ code: "RESOURCE_EXHAUSTED" });

    writer.abort(new Error("cancelled"));
    await expect(first).rejects.toThrow("cancelled");
  });
});
