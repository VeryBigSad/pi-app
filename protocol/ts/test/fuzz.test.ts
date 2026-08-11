import { randomUUID } from "node:crypto";
import fc from "fast-check";
import { describe, expect, it } from "vitest";
import {
  FrameDecoder,
  FrameKind,
  ProtocolError,
  ContiguousStream,
  MAX_FRAME_PAYLOAD_BYTES,
  canonicalizeJson,
  decodeFrame,
  decodeStreamPayload,
  decodeTerminalPayload,
  encodeFrame,
  encodeStreamPayload,
  encodeTerminalPayload,
} from "../src/index.js";

describe("PIMB property corpus", () => {
  it("round-trips bounded terminal frames under arbitrary fragmentation", () => {
    fc.assert(fc.property(
      fc.bigInt({ min: 0n, max: (1n << 64n) - 1n }),
      fc.bigInt({ min: 0n, max: (1n << 64n) - 1n }),
      fc.uint8Array({ maxLength: 2_048 }),
      fc.array(fc.integer({ min: 1, max: 97 }), { maxLength: 40 }),
      (generation, sequence, data, fragmentSizes) => {
        const encoded = encodeFrame(FrameKind.TerminalBytes, encodeTerminalPayload({ terminalGeneration: generation, sequence, data }));
        const decoder = new FrameDecoder();
        const decoded = [];
        let offset = 0;
        for (const requested of fragmentSizes) {
          if (offset >= encoded.length) break;
          const end = Math.min(encoded.length, offset + requested);
          decoded.push(...decoder.push(encoded.subarray(offset, end)));
          offset = end;
        }
        decoded.push(...decoder.push(encoded.subarray(offset)));
        decoder.finish();
        expect(decoded).toHaveLength(1);
        expect(decodeTerminalPayload(decoded[0]!.payload)).toEqual({ terminalGeneration: generation, sequence, data });
      },
    ), { numRuns: 300 });
  });

  it("round-trips stream prefixes and bytes", () => {
    fc.assert(fc.property(
      fc.integer({ min: 0, max: 0xffff_ffff }),
      fc.bigInt({ min: 0n, max: (1n << 64n) - 1n }),
      fc.uint8Array({ maxLength: 2_048 }),
      (sequence, offset, data) => {
        const streamId = randomUUID();
        const decoded = decodeStreamPayload(encodeStreamPayload({ streamId, sequence, offset, data }));
        expect(decoded).toEqual({ streamId, sequence, offset, data });
      },
    ), { numRuns: 300 });
  });

  it("rejects every nonzero reserved flag mutation", () => {
    fc.assert(fc.property(fc.integer({ min: 1, max: 0xffff }), (flags) => {
      const frame = encodeFrame(FrameKind.Json, new TextEncoder().encode("{}"));
      new DataView(frame.buffer).setUint16(6, flags, false);
      expect(() => decodeFrame(frame)).toThrow(ProtocolError);
    }), { numRuns: 200 });
  });

  it("never buffers beyond one bounded frame for arbitrary hostile bytes", () => {
    fc.assert(fc.property(fc.array(fc.uint8Array({ maxLength: 257 }), { maxLength: 32 }), (chunks) => {
      const decoder = new FrameDecoder();
      try {
        chunks.forEach((chunk) => {
          decoder.push(chunk);
          expect(decoder.bufferedBytes()).toBeLessThanOrEqual(12 + MAX_FRAME_PAYLOAD_BYTES);
        });
        decoder.finish();
      } catch (error) {
        expect(error).toBeInstanceOf(ProtocolError);
        expect(decoder.bufferedBytes()).toBeLessThanOrEqual(12 + MAX_FRAME_PAYLOAD_BYTES);
      }
    }), { numRuns: 500 });
  });

  it("coalesces arbitrary bounded frame sequences", () => {
    fc.assert(fc.property(fc.array(fc.uint8Array({ maxLength: 512 }), { minLength: 1, maxLength: 24 }), (payloads) => {
      const encoded = Buffer.concat(payloads.map((payload) => Buffer.from(encodeFrame(FrameKind.Json, payload))));
      const decoder = new FrameDecoder();
      const frames = decoder.push(encoded);
      decoder.finish();
      expect(frames.map(({ payload }) => payload)).toEqual(payloads);
    }), { numRuns: 200 });
  });

  it("canonicalizes arbitrary finite doubles with shortest round-trip semantics", () => {
    fc.assert(fc.property(fc.double({ noNaN: true, noDefaultInfinity: true }), (value) => {
      const canonical = canonicalizeJson(value);
      expect(canonical).toBe(JSON.stringify(value));
      const parsed = JSON.parse(canonical) as number;
      expect(Object.is(parsed, value) || (value === 0 && parsed === 0)).toBe(true);
      expect(canonicalizeJson(parsed)).toBe(canonical);
    }), { numRuns: 500 });
  });

  it("accepts only contiguous stream partitions", () => {
    fc.assert(fc.property(fc.uint8Array({ maxLength: 4_096 }), fc.array(fc.integer({ min: 0, max: 4_096 }), { maxLength: 32 }), (data, cuts) => {
      const streamId = randomUUID();
      const stream = new ContiguousStream(streamId, BigInt(data.length));
      const points = [...new Set([0, ...cuts.map((cut) => Math.min(cut, data.length)), data.length])].sort((left, right) => left - right);
      let sequence = 0;
      for (let index = 1; index < points.length; index += 1) {
        const start = points[index - 1]!;
        const end = points[index]!;
        if (start === end) continue;
        stream.accept(streamId, sequence++, BigInt(start), data.subarray(start, end));
      }
      expect(stream.length).toBe(BigInt(data.length));
    }), { numRuns: 300 });
  });
});
