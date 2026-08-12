import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  ApprovalOffer,
  AssistantMessageAssembler,
  ContiguousStream,
  ProtocolError,
  RecoveryCursor,
  SnapshotAttempt,
  VoicePcmStream,
  assertApprovalBinding,
  assertPairingBinding,
  assertPairingToken,
  assertUnlockBinding,
  assertPromptImageRef,
  assertTerminalHistory,
  assertWireMessage,
} from "../src/index.js";
import type { ApprovalBinding, ImageRef, JsonObject, PairingBinding, ProtocolErrorCode, ReadyBlob, UnlockBinding } from "../src/index.js";

interface Corpus {
  readonly streamOrderCases: readonly StreamCase[];
  readonly voiceStreamCases: readonly VoiceStreamCase[];
  readonly pairingBindingCases: readonly BindingCase<PairingBinding>[];
  readonly unlockBindingCases: readonly BindingCase<UnlockBinding>[];
  readonly pairingTokenCases: readonly { name: string; pairingToken: string; sessionBinding: string; valid: boolean }[];
  readonly approvalCases: readonly BindingCase<ApprovalBinding>[];
  readonly approvalLifecycleCases: readonly { name: string; binding: ApprovalBinding; expiresAtEpochMilliseconds: number; decisions: readonly { binding: ApprovalBinding; nowEpochMilliseconds: number }[]; valid: boolean; expectedError?: ProtocolErrorCode }[];
  readonly terminalHistoryCases: readonly { name: string; text: string; maxLines: number; maxBytes: number; valid: boolean }[];
  readonly promptImageCases: readonly { name: string; blob: ReadyBlob; ref: ImageRef; deviceId: string; nowEpochMilliseconds: number; valid: boolean }[];
  readonly recoveryCursorCases: readonly { name: string; streamEpoch: string; sequence: string; events: readonly { streamEpoch: string; sequence: string; result?: string }[]; leafId: string | null; valid: boolean; expectedError?: ProtocolErrorCode }[];
  readonly snapshotRecoveryCases: readonly { name: string; sessionId: string; streamEpoch: string; frozenSequence: string; leafId: string | null; lastAppendId: string | null; adjunct?: { streamEpoch: string; sequence: string }; validation?: { newAppendEntries: number; leafId: string | null }; publish: boolean; postFence: readonly { streamEpoch: string; sequence: string; result?: string }[]; valid: boolean; expectedError?: ProtocolErrorCode }[];
  readonly assistantCases: readonly { name: string; records: readonly JsonObject[]; provisionalBeforeEnd?: unknown; committed?: JsonObject; valid: boolean; recovery?: boolean }[];
  readonly wireMessageCases: readonly { name: string; type: string; body: JsonObject; valid: boolean; expectedError?: ProtocolErrorCode }[];
}

interface StreamCase {
  readonly name: string;
  readonly limit: string;
  readonly expectedLength?: string;
  readonly expectedSha256?: string;
  readonly chunks: readonly StreamChunk[];
  readonly close?: { length: string; sha256: string };
  readonly afterClose?: StreamChunk;
  readonly valid: boolean;
}

interface StreamChunk {
  readonly streamId: string;
  readonly sequence: number;
  readonly offset: string;
  readonly dataHex?: string;
  readonly dataBytes?: number;
}

interface VoiceBoundary {
  readonly streamId: string;
  readonly chunkSequence: string;
  readonly final: boolean;
}

interface VoiceStreamCase {
  readonly name: string;
  readonly frames: readonly StreamChunk[];
  readonly boundaries: readonly VoiceBoundary[];
  readonly moreFrames: readonly StreamChunk[];
  readonly finalBoundary?: VoiceBoundary;
  readonly valid: boolean;
}

interface BindingCase<T> {
  readonly name: string;
  readonly expected: T;
  readonly actual: T;
  readonly valid: boolean;
}

const fixturePath = resolve(dirname(fileURLToPath(import.meta.url)), "../../fixtures/pimb-v1.json");
const corpus = JSON.parse(await readFile(fixturePath, "utf8")) as Corpus;

function expectValidity(valid: boolean, block: () => unknown): void {
  if (valid) expect(block).not.toThrow();
  else expect(block).toThrow(ProtocolError);
}

function bytes(chunk: StreamChunk): Uint8Array {
  return chunk.dataBytes === undefined
    ? Uint8Array.from(Buffer.from(chunk.dataHex ?? "", "hex"))
    : new Uint8Array(chunk.dataBytes);
}

function accept(stream: ContiguousStream, chunk: StreamChunk): void {
  stream.accept(chunk.streamId, chunk.sequence, BigInt(chunk.offset), bytes(chunk));
}

describe("shared semantic conformance fixtures", () => {
  for (const fixture of corpus.streamOrderCases) {
    it(`stream order: ${fixture.name}`, () => {
      expectValidity(fixture.valid, () => {
        const stream = new ContiguousStream(
          "550e8400-e29b-41d4-a716-446655440001",
          BigInt(fixture.limit),
          fixture.expectedLength === undefined ? undefined : BigInt(fixture.expectedLength),
          fixture.expectedSha256,
        );
        fixture.chunks.forEach((chunk) => accept(stream, chunk));
        if (fixture.close !== undefined) stream.close(BigInt(fixture.close.length), fixture.close.sha256);
        if (fixture.afterClose !== undefined) accept(stream, fixture.afterClose);
      });
    });
  }

  for (const fixture of corpus.voiceStreamCases) {
    it(`voice stream: ${fixture.name}`, () => {
      expectValidity(fixture.valid, () => {
        const stream = new VoicePcmStream("550e8400-e29b-41d4-a716-446655440001");
        for (const frame of fixture.frames) stream.accept(frame.streamId, frame.sequence, BigInt(frame.offset), bytes(frame));
        for (const boundary of fixture.boundaries) stream.boundary(boundary.streamId, boundary.chunkSequence, boundary.final);
        for (const frame of fixture.moreFrames) stream.accept(frame.streamId, frame.sequence, BigInt(frame.offset), bytes(frame));
        const boundary = fixture.finalBoundary;
        if (boundary !== undefined) stream.boundary(boundary.streamId, boundary.chunkSequence, boundary.final);
      });
    });
  }

  for (const fixture of corpus.pairingBindingCases) {
    it(`pairing binding: ${fixture.name}`, () => expectValidity(fixture.valid, () => assertPairingBinding(fixture.expected, fixture.actual)));
  }

  for (const fixture of corpus.unlockBindingCases) {
    it(`unlock binding: ${fixture.name}`, () => expectValidity(fixture.valid, () => assertUnlockBinding(fixture.expected, fixture.actual)));
  }

  for (const fixture of corpus.pairingTokenCases) {
    it(`pairing token: ${fixture.name}`, () => expectValidity(fixture.valid, () => assertPairingToken(fixture.pairingToken, fixture.sessionBinding)));
  }

  for (const fixture of corpus.approvalCases) {
    it(`approval binding: ${fixture.name}`, () => expectValidity(fixture.valid, () => assertApprovalBinding(fixture.expected, fixture.actual)));
  }

  for (const fixture of corpus.approvalLifecycleCases) {
    it(`approval lifecycle: ${fixture.name}`, () => {
      const run = () => {
        const offer = new ApprovalOffer(fixture.binding, fixture.expiresAtEpochMilliseconds);
        fixture.decisions.forEach((decision) => offer.decide(decision.binding, decision.nowEpochMilliseconds));
      };
      expectValidity(fixture.valid, run);
      if (!fixture.valid && fixture.expectedError !== undefined) {
        try {
          run();
          expect.fail("Expected approval failure");
        } catch (error) {
          expect((error as ProtocolError).code).toBe(fixture.expectedError);
        }
      }
    });
  }

  for (const fixture of corpus.terminalHistoryCases) {
    it(`terminal history: ${fixture.name}`, () => expectValidity(fixture.valid, () => assertTerminalHistory(fixture.text, fixture.maxLines, fixture.maxBytes)));
  }

  for (const fixture of corpus.promptImageCases) {
    it(`prompt image: ${fixture.name}`, () => expectValidity(fixture.valid, () => assertPromptImageRef(fixture.blob, fixture.ref, fixture.deviceId, fixture.nowEpochMilliseconds)));
  }

  for (const fixture of corpus.recoveryCursorCases) {
    it(`recovery cursor: ${fixture.name}`, () => {
      expectValidity(fixture.valid, () => {
        const cursor = new RecoveryCursor(fixture.streamEpoch, fixture.sequence);
        for (const event of fixture.events) expect(cursor.accept(event.streamEpoch, event.sequence)).toBe(event.result);
        expect(cursor.snapshot(fixture.leafId).leafId).toBe(fixture.leafId);
      });
      if (!fixture.valid && fixture.expectedError !== undefined) {
        try {
          const cursor = new RecoveryCursor(fixture.streamEpoch, fixture.sequence);
          fixture.events.forEach((event) => cursor.accept(event.streamEpoch, event.sequence));
          cursor.snapshot(fixture.leafId);
          expect.fail("Expected cursor failure");
        } catch (error) {
          expect((error as ProtocolError).code).toBe(fixture.expectedError);
        }
      }
    });
  }

  for (const fixture of corpus.snapshotRecoveryCases) {
    it(`snapshot recovery: ${fixture.name}`, () => {
      const run = () => {
        const attempt = new SnapshotAttempt(fixture.sessionId, fixture.streamEpoch, fixture.frozenSequence, fixture.leafId, fixture.lastAppendId);
        if (fixture.adjunct !== undefined) attempt.acceptAdjunct(fixture.adjunct.streamEpoch, fixture.adjunct.sequence);
        if (fixture.validation !== undefined) attempt.validate(fixture.validation.newAppendEntries, fixture.validation.leafId);
        if (fixture.publish) attempt.publish();
        for (const event of fixture.postFence) expect(attempt.acceptPostFence(event.streamEpoch, event.sequence)).toBe(event.result);
      };
      expectValidity(fixture.valid, run);
      if (!fixture.valid && fixture.expectedError !== undefined) {
        try {
          run();
          expect.fail("Expected snapshot failure");
        } catch (error) {
          expect((error as ProtocolError).code).toBe(fixture.expectedError);
        }
      }
    });
  }

  for (const fixture of corpus.wireMessageCases) {
    it(`wire message: ${fixture.name}`, () => {
      expectValidity(fixture.valid, () => assertWireMessage(fixture.type, fixture.body));
      if (!fixture.valid && fixture.expectedError !== undefined) {
        try {
          assertWireMessage(fixture.type, fixture.body);
          expect.fail("Expected wire message failure");
        } catch (error) {
          expect((error as ProtocolError).code).toBe(fixture.expectedError);
        }
      }
    });
  }

  for (const fixture of corpus.assistantCases) {
    it(`assistant assembly: ${fixture.name}`, () => {
      const assembler = new AssistantMessageAssembler();
      expectValidity(fixture.valid, () => {
        for (const record of fixture.records) {
          if (record["type"] === "message_end" && fixture.provisionalBeforeEnd !== undefined) expect(assembler.provisional()).toEqual(fixture.provisionalBeforeEnd);
          assembler.apply(record);
        }
        expect(assembler.committed()).toEqual(fixture.committed);
      });
      if (!fixture.valid) expect(assembler.needsRecovery()).toBe(fixture.recovery);
    });
  }
});
