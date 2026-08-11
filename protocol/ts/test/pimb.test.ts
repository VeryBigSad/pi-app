import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  FrameDecoder,
  FrameKind,
  HARD_BOUNDS,
  MAX_BINARY_DATA_BYTES,
  MAX_FRAME_PAYLOAD_BYTES,
  MAX_JSON_PAYLOAD_BYTES,
  ProtocolError,
  assertEnvelope,
  assertLeafId,
  canonicalizeJson,
  commandPayloadHash,
  decodeJsonPayload,
  decodeStreamPayload,
  decodeTerminalPayload,
  encodeFrame,
  encodeJsonPayload,
  encodeStreamPayload,
  parseUint64,
  projectRawPiJson,
  verifyInlineRawRecord,
} from "../src/index.js";
import type { JsonObject, JsonValue, ProtocolErrorCode } from "../src/index.js";

interface FrameExpected {
  readonly kind: "JSON" | "BLOB_CHUNK" | "AUDIO_PCM" | "TERMINAL_BYTES";
  readonly streamId?: string;
  readonly sequence?: number | string;
  readonly offset?: string;
  readonly terminalGeneration?: string;
  readonly dataHex?: string;
  readonly envelope?: object;
}

interface Corpus {
  readonly hardBounds: Record<string, number>;
  readonly frames: readonly { readonly name: string; readonly chunksHex: readonly string[]; readonly expected?: readonly FrameExpected[]; readonly errorStage?: "frame" | "finish" | "json"; readonly expectedError?: ProtocolErrorCode }[];
  readonly uint64Cases: readonly { readonly value: string; readonly valid: boolean }[];
  readonly leafCases: readonly { readonly value: string | null; readonly valid: boolean }[];
  readonly hashes: readonly { readonly sessionId: string; readonly operation: string; readonly payload: JsonObject; readonly expectedLeafId?: string | null; readonly includeExpectedLeafId: boolean; readonly sha256: string }[];
  readonly jcsNumberCases: readonly { readonly name: string; readonly lexeme: string; readonly canonical: string }[];
  readonly envelopeCases: readonly { readonly name: string; readonly json: string; readonly valid: boolean; readonly expectedError?: ProtocolErrorCode }[];
  readonly rawRecords: readonly { readonly rawJson: string; readonly rawSize: string; readonly rawSha256: string; readonly projection: JsonObject }[];
}

const fixturePath = resolve(dirname(fileURLToPath(import.meta.url)), "../../fixtures/pimb-v1.json");
const corpus = JSON.parse(await readFile(fixturePath, "utf8")) as Corpus;

function bytes(hex: string): Uint8Array {
  return Uint8Array.from(Buffer.from(hex, "hex"));
}

function consume(chunks: readonly string[], finish = true) {
  const decoder = new FrameDecoder();
  const frames = chunks.flatMap((hex) => decoder.push(bytes(hex)));
  if (finish) decoder.finish();
  return { decoder, frames };
}

function expectProtocolError(block: () => unknown, code: ProtocolErrorCode): void {
  try {
    block();
    expect.fail(`Expected ${code}`);
  } catch (error) {
    expect(error).toBeInstanceOf(ProtocolError);
    expect((error as ProtocolError).code).toBe(code);
  }
}

describe("shared PIMB fixture corpus", () => {
  for (const fixture of corpus.frames) {
    it(fixture.name, () => {
      if (fixture.expectedError !== undefined) {
        if (fixture.errorStage === "json") {
          const frame = consume(fixture.chunksHex).frames[0]!;
          expectProtocolError(() => decodeJsonPayload(frame.payload), fixture.expectedError!);
        } else if (fixture.errorStage === "finish") {
          const { decoder } = consume(fixture.chunksHex, false);
          expectProtocolError(() => decoder.finish(), fixture.expectedError);
        } else {
          expectProtocolError(() => consume(fixture.chunksHex), fixture.expectedError);
        }
        return;
      }
      const decoded = consume(fixture.chunksHex).frames;
      expect(decoded).toHaveLength(fixture.expected!.length);
      fixture.expected!.forEach((expected, index) => {
        const actual = decoded[index]!;
        const kind = actual.kind === FrameKind.Json ? "JSON" : actual.kind === FrameKind.BlobChunk ? "BLOB_CHUNK" : actual.kind === FrameKind.AudioPcm ? "AUDIO_PCM" : "TERMINAL_BYTES";
        expect(kind).toBe(expected.kind);
        if (actual.kind === FrameKind.Json) expect(decodeJsonPayload(actual.payload)).toEqual(expected.envelope);
        if (actual.kind === FrameKind.BlobChunk || actual.kind === FrameKind.AudioPcm) {
          const value = decodeStreamPayload(actual.payload);
          expect({ ...value, offset: value.offset.toString(), data: Buffer.from(value.data).toString("hex") }).toEqual({ streamId: expected.streamId, sequence: expected.sequence, offset: expected.offset, data: expected.dataHex });
        }
        if (actual.kind === FrameKind.TerminalBytes) {
          const value = decodeTerminalPayload(actual.payload);
          expect({ terminalGeneration: value.terminalGeneration.toString(), sequence: value.sequence.toString(), data: Buffer.from(value.data).toString("hex") }).toEqual({ terminalGeneration: expected.terminalGeneration, sequence: expected.sequence, data: expected.dataHex });
        }
      });
    });
  }

  it("keeps every frozen hard bound identical", () => {
    expect(corpus.hardBounds).toEqual(HARD_BOUNDS);
  });

  it("accepts exact frame bounds and rejects one byte over before buffering", () => {
    const json = new Uint8Array(MAX_JSON_PAYLOAD_BYTES);
    expect(encodeFrame(FrameKind.Json, json)).toHaveLength(12 + MAX_JSON_PAYLOAD_BYTES);
    expectProtocolError(() => encodeFrame(FrameKind.Json, new Uint8Array(MAX_JSON_PAYLOAD_BYTES + 1)), "FRAME_TOO_LARGE");
    const stream = encodeStreamPayload({ streamId: "550e8400-e29b-41d4-a716-446655440001", sequence: 0, offset: 0n, data: new Uint8Array(MAX_BINARY_DATA_BYTES) });
    expect(stream).toHaveLength(28 + MAX_BINARY_DATA_BYTES);
    expectProtocolError(() => encodeStreamPayload({ streamId: "550e8400-e29b-41d4-a716-446655440001", sequence: 0, offset: 0n, data: new Uint8Array(MAX_BINARY_DATA_BYTES + 1) }), "PROTOCOL_VIOLATION");
    const decoder = new FrameDecoder();
    expectProtocolError(() => decoder.push(bytes("50494d420101000000100001")), "FRAME_TOO_LARGE");
    expect(decoder.bufferedBytes()).toBeLessThanOrEqual(12);
  });

  it("accepts repeated object aliases but rejects cycles before encoding", () => {
    const shared = { value: true };
    expect(() => encodeJsonPayload({ left: shared, right: shared })).not.toThrow();
    const cyclic: Record<string, unknown> = {};
    cyclic["self"] = cyclic;
    expectProtocolError(() => encodeJsonPayload(cyclic), "PROTOCOL_VIOLATION");
  });

  it("shares canonical uint64 and leaf variants", () => {
    for (const fixture of corpus.uint64Cases) {
      if (fixture.valid) expect(parseUint64(fixture.value).toString()).toBe(fixture.value);
      else expectProtocolError(() => parseUint64(fixture.value), "PROTOCOL_VIOLATION");
    }
    for (const fixture of corpus.leafCases) {
      if (fixture.valid) expect(() => assertLeafId(fixture.value)).not.toThrow();
      else expectProtocolError(() => assertLeafId(fixture.value), "PROTOCOL_VIOLATION");
    }
  });

  it("shares RFC 8785 number canonicalization fixtures", () => {
    for (const fixture of corpus.jcsNumberCases) {
      expect(canonicalizeJson(JSON.parse(fixture.lexeme) as JsonValue), fixture.name).toBe(fixture.canonical);
    }
  });

  it("keeps envelope validation layered above frame JSON parsing", () => {
    for (const fixture of corpus.envelopeCases) {
      const value = JSON.parse(fixture.json) as JsonValue;
      expect(decodeJsonPayload(new TextEncoder().encode(fixture.json)), fixture.name).toEqual(value);
      if (fixture.valid) {
        expect(() => assertEnvelope(value), fixture.name).not.toThrow();
      } else {
        const expectedError = fixture.expectedError;
        expect(expectedError, fixture.name).toBeDefined();
        if (expectedError !== undefined) expectProtocolError(() => assertEnvelope(value), expectedError);
      }
    }
  });

  it("shares RFC 8785 command hashes for absent, null, leaf, unicode, and image refs", () => {
    for (const fixture of corpus.hashes) {
      const input = { sessionId: fixture.sessionId, operation: fixture.operation, payload: fixture.payload } as { sessionId: string; operation: string; payload: JsonObject; expectedLeafId?: string | null };
      if (fixture.includeExpectedLeafId) input.expectedLeafId = fixture.expectedLeafId ?? null;
      expect(commandPayloadHash(input)).toBe(fixture.sha256);
    }
  });

  it("shares exact raw UTF-8 projection, size, and digest", () => {
    for (const fixture of corpus.rawRecords) {
      const record = projectRawPiJson(new TextEncoder().encode(fixture.rawJson));
      expect(record).toEqual(fixture);
      expect(() => verifyInlineRawRecord(record)).not.toThrow();
      expectProtocolError(() => verifyInlineRawRecord({ ...record, rawSha256: "0".repeat(64) }), "PROTOCOL_VIOLATION");
    }
  });
});
