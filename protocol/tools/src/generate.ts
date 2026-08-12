import { createHash } from "node:crypto";
import { writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const fixturePath = resolve(here, "../../fixtures/pimb-v1.json");
const encoder = new TextEncoder();
const ids = {
  message: "550e8400-e29b-41d4-a716-446655440000",
  stream: "550e8400-e29b-41d4-a716-446655440001",
  session: "550e8400-e29b-41d4-a716-446655440002",
  epoch: "550e8400-e29b-41d4-a716-446655440003",
  invitation: "550e8400-e29b-41d4-a716-446655440004",
  ceremony: "550e8400-e29b-41d4-a716-446655440005",
  offer: "550e8400-e29b-41d4-a716-446655440006",
  blob: "550e8400-e29b-41d4-a716-446655440007",
  device: "550e8400-e29b-41d4-a716-446655440008",
  command: "550e8400-e29b-41d4-a716-446655440009",
  macInstance: "550e8400-e29b-41d4-a716-44665544000a",
  parentSession: "550e8400-e29b-41d4-a716-44665544000b",
};
const zeroHash = "0".repeat(64);
const oneHash = "1".repeat(64);
const nonce = "A".repeat(43);
const pairingTokenBytes = Uint8Array.from({ length: 32 }, (_, index) => index);
const pairingToken = Buffer.from(pairingTokenBytes).toString("base64url");

function hex(bytes: Uint8Array): string {
  return Buffer.from(bytes).toString("hex");
}

function concat(...values: Uint8Array[]): Uint8Array {
  const output = new Uint8Array(values.reduce((sum, value) => sum + value.length, 0));
  let offset = 0;
  for (const value of values) {
    output.set(value, offset);
    offset += value.length;
  }
  return output;
}

function frame(kind: number, payload: Uint8Array): Uint8Array {
  const value = new Uint8Array(12 + payload.length);
  value.set([0x50, 0x49, 0x4d, 0x42, 1, kind, 0, 0]);
  new DataView(value.buffer).setUint32(8, payload.length, false);
  value.set(payload, 12);
  return value;
}

function streamPayload(sequence: number, offset: bigint, data: Uint8Array): Uint8Array {
  const value = new Uint8Array(28 + data.length);
  value.set(uuid(ids.stream));
  new DataView(value.buffer).setUint32(16, sequence, false);
  new DataView(value.buffer).setBigUint64(20, offset, false);
  value.set(data, 28);
  return value;
}

function terminalPayload(generation: bigint, sequence: bigint, data: Uint8Array): Uint8Array {
  const value = new Uint8Array(16 + data.length);
  new DataView(value.buffer).setBigUint64(0, generation, false);
  new DataView(value.buffer).setBigUint64(8, sequence, false);
  value.set(data, 16);
  return value;
}

function uuid(value: string): Uint8Array {
  return Uint8Array.from(Buffer.from(value.replaceAll("-", ""), "hex"));
}

function chunks(value: Uint8Array, sizes: readonly number[]): string[] {
  const result: string[] = [];
  let offset = 0;
  for (const size of sizes) {
    if (offset >= value.length) break;
    const end = Math.min(offset + size, value.length);
    result.push(hex(value.subarray(offset, end)));
    offset = end;
  }
  if (offset < value.length) result.push(hex(value.subarray(offset)));
  return result;
}

function malformedHeader(mutator: (value: Uint8Array) => void): string {
  const value = frame(1, encoder.encode("{}"));
  mutator(value);
  return hex(value);
}

function canonical(value: unknown): string {
  if (value === null || typeof value === "boolean" || typeof value === "number" || typeof value === "string") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  const object = value as Record<string, unknown>;
  return `{${Object.keys(object).sort().map((key) => `${JSON.stringify(key)}:${canonical(object[key])}`).join(",")}}`;
}

function sha256(value: Uint8Array | string): string {
  return createHash("sha256").update(value).digest("hex");
}

function commandHash(input: Record<string, unknown>): string {
  return sha256(canonical(input));
}

function envelope(type: string, body: object, suffix = 0): object {
  const messageId = `550e8400-e29b-41d4-a716-44665544${suffix.toString().padStart(4, "0")}`;
  return { v: { major: 1, minor: 0 }, type, messageId, replyTo: null, body };
}

const jsonEnvelope = envelope("ping", { unknown: true });
const jsonFrame = frame(1, encoder.encode(JSON.stringify(jsonEnvelope)));
const nonEnvelopeJson = { unrelated: true };
const nonEnvelopeFrame = frame(1, encoder.encode(JSON.stringify(nonEnvelopeJson)));

const jcsNumberLexemes = [
  "0", "-0", "1", "-1", "0.1", "-0.1", "0.30000000000000004", "3.141592653589793",
  "100", "1e+2", "1e+20", "1e+21", "-1e+21", "1e-6", "1e-7", "-1e-7",
  "5e-324", "4.9406564584124654e-324", "2.2250738585072014e-308",
  "1.7976931348623157e+308", "-1.7976931348623157e+308",
  "9007199254740992", "9007199254740993", "1.0000000000000002",
  "1.2345678912345678e+20", "123456789.25", "1.5e+300", "7.120236347223045e-307",
];
const jcsNumberCases = jcsNumberLexemes.map((lexeme) => ({ name: lexeme, lexeme, canonical: JSON.stringify(JSON.parse(lexeme)) }));

function envelopeJsonWith(version: string): string {
  return `{"v":${version},"type":"ping","messageId":"${ids.message}","replyTo":null,"body":{}}`;
}
const envelopeCases = [
  { name: "baseline", json: envelopeJsonWith('{"major":1,"minor":0}'), valid: true },
  { name: "decimal-version-lexemes", json: envelopeJsonWith('{"major":1.0,"minor":0.0}'), valid: true },
  { name: "exponent-version-lexemes", json: envelopeJsonWith('{"major":1e0,"minor":0e0}'), valid: true },
  { name: "fractional-major", json: envelopeJsonWith('{"major":1.5,"minor":0}'), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "unsupported-major", json: envelopeJsonWith('{"major":2,"minor":0}'), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "missing-version", json: `{"type":"ping","messageId":"${ids.message}","replyTo":null,"body":{}}`, valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "scalar-version", json: envelopeJsonWith("1"), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "string-major", json: envelopeJsonWith('{"major":"1","minor":0}'), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "fractional-minor", json: envelopeJsonWith('{"major":1,"minor":0.5}'), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "minor-over-byte", json: envelopeJsonWith('{"major":1,"minor":256}'), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "negative-minor", json: envelopeJsonWith('{"major":1,"minor":-1}'), valid: false, expectedError: "UNSUPPORTED_VERSION" },
  { name: "uppercase-type", json: `{"v":{"major":1,"minor":0},"type":"Ping","messageId":"${ids.message}","replyTo":null,"body":{}}`, valid: false, expectedError: "PROTOCOL_VIOLATION" },
  { name: "missing-message-id", json: `{"v":{"major":1,"minor":0},"type":"ping","replyTo":null,"body":{}}`, valid: false, expectedError: "PROTOCOL_VIOLATION" },
  { name: "non-v4-message-id", json: `{"v":{"major":1,"minor":0},"type":"ping","messageId":"550e8400-e29b-11d4-a716-446655440000","replyTo":null,"body":{}}`, valid: false, expectedError: "PROTOCOL_VIOLATION" },
  { name: "invalid-reply-to", json: `{"v":{"major":1,"minor":0},"type":"ping","messageId":"${ids.message}","replyTo":"x","body":{}}`, valid: false, expectedError: "PROTOCOL_VIOLATION" },
  { name: "scalar-body", json: `{"v":{"major":1,"minor":0},"type":"ping","messageId":"${ids.message}","replyTo":null,"body":1}`, valid: false, expectedError: "PROTOCOL_VIOLATION" },
];
const terminalFrame = frame(4, terminalPayload(1n, 2n, Uint8Array.of(0x1b, 0x5b, 0x41)));
const exactRawJson = "{\"type\":\"message_update\",\"assistantMessageEvent\":{},\"ignored\":\"kept raw\"}";
const unicodeRawJson = "{\"type\":\"extension_ui_request\",\"requestId\":\"r-1\",\"message\":\"line\\u2028next π\",\"secret\":true}";
const emptyDigest = sha256(new Uint8Array());
const dataDigest = sha256(Uint8Array.of(1, 2, 3));
const pairingBinding = {
  ceremonyKind: "registration",
  invitationId: ids.invitation,
  sessionBinding: zeroHash,
  csrSha256: oneHash,
  rpId: "verybigsad.github.io",
  origin: "android:apk-key-hash:release",
  challenge: nonce,
  expiresAt: "2026-08-09T12:00:00Z",
};
const unlockBinding = {
  ceremonyKind: "assertion",
  deviceId: ids.device,
  rpId: "verybigsad.github.io",
  origin: "android:apk-key-hash:release",
  challenge: nonce,
  expiresAt: "2026-08-09T12:00:00Z",
};
const approvalBinding = { offerId: ids.offer, operationId: "tool-call-1", argumentHash: zeroHash };
const sessionCursor = { sessionId: ids.session, streamEpoch: ids.epoch, sequence: "42", leafId: "deadbeef" };
const agent = {
  agentId: "agent-1",
  parentAgentId: "agent-root",
  description: "Inspect protocol parity",
  agentType: "explore",
  status: "running",
  startedAt: "2026-08-11T05:00:00Z",
  toolUses: 3,
  model: "k3-large",
};
const agentsCatalogSession = { sessionId: ids.session, agents: [agent] };
const catalogEntry = {
  sessionId: ids.session,
  provider: "openai",
  model: "gpt-5",
  thinkingLevel: "high",
  repo: "/work/pi-app",
  worktree: null,
  cwd: "/work/pi-app",
  parentId: null,
  createdAt: "2026-08-09T10:00:00Z",
  updatedAt: "2026-08-09T11:00:00Z",
};
const readyBlob = {
  blobId: ids.blob,
  ownerDeviceId: ids.device,
  size: "3",
  sha256: dataDigest,
  mimeType: "image/png",
  expiresAtEpochMilliseconds: 2_000,
  ready: true,
  referenced: false,
};
const imageRef = { blobId: ids.blob, size: "3", sha256: dataDigest, mimeType: "image/png" };

const corpus = {
  version: 1,
  hardBounds: {
    framePayloadBytes: 1_048_576,
    jsonPayloadBytes: 262_144,
    binaryDataBytes: 65_536,
    eventBatchEvents: 128,
    eventBatchBytes: 262_144,
    inlineRawBytes: 131_072,
    outboundQueueFrames: 512,
    outboundQueueBytes: 8_388_608,
    outboundQueueStallMilliseconds: 10_000,
    promptImageBytes: 8_388_608,
    terminalHistoryLines: 5_000,
    terminalHistoryBytes: 1_048_576,
    finalizedMessages: 500,
    piRecordBytes: 16_777_216,
    replayEventsPerSession: 10_000,
    replayBytesPerSession: 67_108_864,
    replayBytesGlobal: 268_435_456,
    replayRetentionMilliseconds: 86_400_000,
    rawReferenceStoreBytes: 536_870_912,
    rawReferenceRetentionMilliseconds: 2_592_000_000,
    promptBlobBytesPerDevice: 67_108_864,
    promptBlobBytesGlobal: 268_435_456,
    promptBlobConcurrentUploads: 32,
    promptBlobOrphanMilliseconds: 900_000,
    promptBlobDormantMilliseconds: 86_400_000,
    promptBlobTerminalMilliseconds: 3_600_000,
    journalDormantMilliseconds: 86_400_000,
    journalFullRetentionMilliseconds: 2_592_000_000,
    journalTombstoneRetentionMilliseconds: 31_536_000_000,
    journalTombstoneRows: 100_000,
    androidCacheMessages: 50_000,
    androidCacheBytes: 536_870_912,
    backgroundLeaseMilliseconds: 300_000,
    routeJsonBytes: 16_384,
    routeNonceBytes: 32,
    routeChallengeMilliseconds: 30_000,
    routeReplayMilliseconds: 120_000,
    routeHeartbeatMilliseconds: 30_000,
    routeHeartbeatTimeoutMilliseconds: 90_000,
    routeRendezvousMilliseconds: 20_000,
    routeKeyOverlapMilliseconds: 86_400_000,
    pairingInvitationBytes: 2_048,
    pairingInvitationMilliseconds: 300_000,
    approvalQueueEntries: 8,
    approvalPromotionMilliseconds: 30_000,
    approvalDecisionMilliseconds: 120_000,
    approvalHookMilliseconds: 150_000,
    voiceBodyBytes: 65_536,
    voiceTextChars: 16_384,
    maxAgents: 256,
  },
  frames: [
    { name: "fragmented-json-every-boundary", chunksHex: chunks(jsonFrame, Array.from({ length: jsonFrame.length }, () => 1)), expected: [{ kind: "JSON", envelope: jsonEnvelope }] },
    { name: "coalesced-json-terminal", chunksHex: [hex(concat(jsonFrame, terminalFrame))], expected: [{ kind: "JSON", envelope: jsonEnvelope }, { kind: "TERMINAL_BYTES", terminalGeneration: "1", sequence: "2", dataHex: "1b5b41" }] },
    { name: "json-non-envelope-object", chunksHex: [hex(nonEnvelopeFrame)], expected: [{ kind: "JSON", envelope: nonEnvelopeJson }] },
    { name: "blob-chunk-uint64-max", chunksHex: chunks(frame(2, streamPayload(0xffff_ffff, 0xffff_ffff_ffff_ffffn, Uint8Array.of(1, 2, 3))), [3, 2, 7, 1, 19]), expected: [{ kind: "BLOB_CHUNK", streamId: ids.stream, sequence: 0xffff_ffff, offset: "18446744073709551615", dataHex: "010203" }] },
    { name: "audio-pcm-empty", chunksHex: [hex(frame(3, streamPayload(0, 0n, new Uint8Array())))], expected: [{ kind: "AUDIO_PCM", streamId: ids.stream, sequence: 0, offset: "0", dataHex: "" }] },
    { name: "terminal-uint64-max", chunksHex: [hex(frame(4, terminalPayload(0xffff_ffff_ffff_ffffn, 0xffff_ffff_ffff_ffffn, new Uint8Array())))], expected: [{ kind: "TERMINAL_BYTES", terminalGeneration: "18446744073709551615", sequence: "18446744073709551615", dataHex: "" }] },
    { name: "invalid-utf8-json", chunksHex: [hex(frame(1, Uint8Array.of(0xc3)))], errorStage: "json", expectedError: "PROTOCOL_VIOLATION" },
    { name: "malformed-json", chunksHex: [hex(frame(1, encoder.encode("{")))], errorStage: "json", expectedError: "PROTOCOL_VIOLATION" },
    { name: "json-primitive", chunksHex: [hex(frame(1, encoder.encode("1")))], errorStage: "json", expectedError: "PROTOCOL_VIOLATION" },
    { name: "invalid-magic", chunksHex: [malformedHeader((value) => { value[0] = 0; })], errorStage: "frame", expectedError: "PROTOCOL_VIOLATION" },
    { name: "unsupported-major", chunksHex: [malformedHeader((value) => { value[4] = 2; })], errorStage: "frame", expectedError: "UNSUPPORTED_VERSION" },
    { name: "invalid-kind", chunksHex: [malformedHeader((value) => { value[5] = 0xff; })], errorStage: "frame", expectedError: "PROTOCOL_VIOLATION" },
    { name: "nonzero-flags", chunksHex: [malformedHeader((value) => { value[7] = 1; })], errorStage: "frame", expectedError: "PROTOCOL_VIOLATION" },
    { name: "oversized-declared-frame", chunksHex: ["50494d420101000000100001"], errorStage: "frame", expectedError: "FRAME_TOO_LARGE" },
    { name: "oversized-declared-json", chunksHex: ["50494d420101000000040001"], errorStage: "frame", expectedError: "FRAME_TOO_LARGE" },
    { name: "short-stream-prefix", chunksHex: ["50494d42010200000000001b"], errorStage: "frame", expectedError: "PROTOCOL_VIOLATION" },
    { name: "truncated-header", chunksHex: ["50494d"], errorStage: "finish", expectedError: "PROTOCOL_VIOLATION" },
    { name: "truncated-payload", chunksHex: ["50494d4201010000000000027b"], errorStage: "finish", expectedError: "PROTOCOL_VIOLATION" }
  ],
  uint64Cases: [
    { value: "0", valid: true }, { value: "1", valid: true }, { value: "18446744073709551615", valid: true },
    { value: "", valid: false }, { value: "00", valid: false }, { value: "01", valid: false }, { value: "+1", valid: false }, { value: "-1", valid: false },
    { value: "18446744073709551616", valid: false }, { value: "99999999999999999999", valid: false }
  ],
  jcsNumberCases,
  envelopeCases,
  leafCases: [
    { value: null, valid: true }, { value: "00000000", valid: true }, { value: "deadbeef", valid: true },
    { value: "", valid: false }, { value: "deadbee", valid: false }, { value: "DEADBEEF", valid: false }, { value: ids.message, valid: false }
  ],
  hashes: [
    (() => { const input = { sessionId: ids.session, operation: "prompt", payload: { message: "Run focused tests", nested: { a: true } } }; return { name: "absent-leaf", ...input, includeExpectedLeafId: false, sha256: commandHash(input) }; })(),
    (() => { const input = { sessionId: ids.session, operation: "prompt", payload: { message: "π", number: 1e21 }, expectedLeafId: null }; return { name: "null-leaf-unicode-number", ...input, includeExpectedLeafId: true, sha256: commandHash(input) }; })(),
    (() => { const input = { sessionId: ids.session, operation: "steer", payload: { imageRef }, expectedLeafId: "7fa3c91e" }; return { name: "image-ref-leaf", ...input, includeExpectedLeafId: true, sha256: commandHash(input) }; })()
  ],
  rawRecords: [
    { rawJson: exactRawJson, rawSize: String(Buffer.byteLength(exactRawJson)), rawSha256: sha256(exactRawJson), projection: { type: "message_update", assistantMessageEvent: {} } },
    { rawJson: unicodeRawJson, rawSize: String(Buffer.byteLength(unicodeRawJson)), rawSha256: sha256(unicodeRawJson), projection: { type: "extension_ui_request", message: "line\u2028next π", requestId: "r-1" } }
  ],
  streamOrderCases: [
    { name: "contiguous-close", limit: "3", expectedLength: "3", expectedSha256: dataDigest, chunks: [{ streamId: ids.stream, sequence: 0, offset: "0", dataHex: "01" }, { streamId: ids.stream, sequence: 1, offset: "1", dataHex: "0203" }], close: { length: "3", sha256: dataDigest }, valid: true },
    { name: "sequence-gap", limit: "3", chunks: [{ streamId: ids.stream, sequence: 1, offset: "0", dataHex: "01" }], valid: false },
    { name: "offset-gap", limit: "3", chunks: [{ streamId: ids.stream, sequence: 0, offset: "1", dataHex: "01" }], valid: false },
    { name: "overflow", limit: "2", chunks: [{ streamId: ids.stream, sequence: 0, offset: "0", dataHex: "010203" }], valid: false },
    { name: "digest-mismatch", limit: "3", chunks: [{ streamId: ids.stream, sequence: 0, offset: "0", dataHex: "010203" }], close: { length: "3", sha256: zeroHash }, valid: false },
    { name: "data-after-close", limit: "0", chunks: [], close: { length: "0", sha256: emptyDigest }, afterClose: { streamId: ids.stream, sequence: 0, offset: "0", dataHex: "" }, valid: false }
  ],
  pairingBindingCases: [
    { name: "registration-bound", expected: pairingBinding, actual: pairingBinding, valid: true },
    { name: "assertion-not-registration", expected: pairingBinding, actual: { ...pairingBinding, ceremonyKind: "assertion" }, valid: false },
    { name: "session-binding-mismatch", expected: pairingBinding, actual: { ...pairingBinding, sessionBinding: oneHash }, valid: false },
    { name: "csr-mismatch", expected: pairingBinding, actual: { ...pairingBinding, csrSha256: zeroHash }, valid: false },
    { name: "invitation-mismatch", expected: pairingBinding, actual: { ...pairingBinding, invitationId: ids.command }, valid: false }
  ],
  unlockBindingCases: [
    { name: "assertion-bound", expected: unlockBinding, actual: unlockBinding, valid: true },
    { name: "device-mismatch", expected: unlockBinding, actual: { ...unlockBinding, deviceId: ids.command }, valid: false },
    { name: "challenge-mismatch", expected: unlockBinding, actual: { ...unlockBinding, challenge: pairingToken }, valid: false },
    { name: "registration-not-assertion", expected: unlockBinding, actual: { ...unlockBinding, ceremonyKind: "registration" }, valid: false },
    { name: "expiry-mismatch", expected: unlockBinding, actual: { ...unlockBinding, expiresAt: "2026-08-09T12:05:00Z" }, valid: false }
  ],
  pairingTokenCases: [
    { name: "valid-token-binding", pairingToken, sessionBinding: sha256(pairingTokenBytes), valid: true },
    { name: "wrong-length-token", pairingToken: pairingToken.slice(0, 42), sessionBinding: sha256(pairingTokenBytes), valid: false },
    { name: "bad-base64url-token", pairingToken: `${pairingToken.slice(0, 42)}+`, sessionBinding: sha256(pairingTokenBytes), valid: false },
    { name: "session-binding-mismatch", pairingToken, sessionBinding: zeroHash, valid: false }
  ],
  approvalCases: [
    { name: "exact-single-use-tuple", expected: approvalBinding, actual: approvalBinding, valid: true },
    { name: "stale-offer", expected: approvalBinding, actual: { ...approvalBinding, offerId: ids.command }, valid: false },
    { name: "wrong-operation", expected: approvalBinding, actual: { ...approvalBinding, operationId: "tool-call-2" }, valid: false },
    { name: "changed-arguments", expected: approvalBinding, actual: { ...approvalBinding, argumentHash: oneHash }, valid: false }
  ],
  approvalLifecycleCases: [
    { name: "allow-once-before-expiry", binding: approvalBinding, expiresAtEpochMilliseconds: 2000, decisions: [{ binding: approvalBinding, nowEpochMilliseconds: 1999 }], valid: true },
    { name: "decision-at-expiry", binding: approvalBinding, expiresAtEpochMilliseconds: 2000, decisions: [{ binding: approvalBinding, nowEpochMilliseconds: 2000 }], valid: false, expectedError: "APPROVAL_EXPIRED" },
    { name: "offer-is-single-use", binding: approvalBinding, expiresAtEpochMilliseconds: 2000, decisions: [{ binding: approvalBinding, nowEpochMilliseconds: 1000 }, { binding: approvalBinding, nowEpochMilliseconds: 1001 }], valid: false, expectedError: "APPROVAL_EXPIRED" },
    { name: "mismatched-live-tuple", binding: approvalBinding, expiresAtEpochMilliseconds: 2000, decisions: [{ binding: { ...approvalBinding, argumentHash: oneHash }, nowEpochMilliseconds: 1000 }], valid: false, expectedError: "APPROVAL_DENIED" }
  ],
  terminalHistoryCases: [
    { name: "bounded-text", text: "one\ntwo", maxLines: 2, maxBytes: 7, valid: true },
    { name: "bounded-trailing-newline", text: "one\ntwo\n", maxLines: 2, maxBytes: 8, valid: true },
    { name: "too-many-lines", text: "one\ntwo", maxLines: 1, maxBytes: 7, valid: false },
    { name: "utf8-byte-overflow", text: "π", maxLines: 1, maxBytes: 1, valid: false },
    { name: "request-line-hard-bound", text: "", maxLines: 5001, maxBytes: 1, valid: false },
    { name: "request-byte-hard-bound", text: "", maxLines: 1, maxBytes: 1048577, valid: false }
  ],
  promptImageCases: [
    { name: "ready-owned-exact", blob: readyBlob, ref: imageRef, deviceId: ids.device, nowEpochMilliseconds: 1000, valid: true },
    { name: "not-ready", blob: { ...readyBlob, ready: false }, ref: imageRef, deviceId: ids.device, nowEpochMilliseconds: 1000, valid: false },
    { name: "cross-device", blob: readyBlob, ref: imageRef, deviceId: ids.command, nowEpochMilliseconds: 1000, valid: false },
    { name: "digest-mismatch", blob: readyBlob, ref: { ...imageRef, sha256: zeroHash }, deviceId: ids.device, nowEpochMilliseconds: 1000, valid: false },
    { name: "expired-orphan", blob: readyBlob, ref: imageRef, deviceId: ids.device, nowEpochMilliseconds: 2000, valid: false },
    { name: "already-referenced", blob: { ...readyBlob, referenced: true }, ref: imageRef, deviceId: ids.device, nowEpochMilliseconds: 1000, valid: false }
  ],
  recoveryCursorCases: [
    { name: "contiguous-and-duplicate", streamEpoch: ids.epoch, sequence: "41", events: [{ streamEpoch: ids.epoch, sequence: "42", result: "applied" }, { streamEpoch: ids.epoch, sequence: "42", result: "duplicate" }], leafId: "deadbeef", valid: true },
    { name: "sequence-gap", streamEpoch: ids.epoch, sequence: "41", events: [{ streamEpoch: ids.epoch, sequence: "43" }], leafId: null, valid: false, expectedError: "SEQUENCE_GAP" },
    { name: "epoch-change", streamEpoch: ids.epoch, sequence: "41", events: [{ streamEpoch: ids.command, sequence: "42" }], leafId: null, valid: false, expectedError: "SYNC_REQUIRED" },
    { name: "invalid-leaf", streamEpoch: ids.epoch, sequence: "41", events: [], leafId: "DEADBEEF", valid: false, expectedError: "PROTOCOL_VIOLATION" }
  ],
  snapshotRecoveryCases: [
    { name: "validated-post-fence-replay", sessionId: ids.session, streamEpoch: ids.epoch, frozenSequence: "50", leafId: "deadbeef", lastAppendId: "4096", adjunct: { streamEpoch: ids.epoch, sequence: "50" }, validation: { newAppendEntries: 0, leafId: "deadbeef" }, publish: true, postFence: [{ streamEpoch: ids.epoch, sequence: "51", result: "applied" }, { streamEpoch: ids.epoch, sequence: "52", result: "applied" }], valid: true },
    { name: "adjunct-cursor-mismatch", sessionId: ids.session, streamEpoch: ids.epoch, frozenSequence: "50", leafId: "deadbeef", lastAppendId: "4096", adjunct: { streamEpoch: ids.epoch, sequence: "51" }, validation: { newAppendEntries: 0, leafId: "deadbeef" }, publish: true, postFence: [], valid: false, expectedError: "SYNC_REQUIRED" },
    { name: "validation-found-append", sessionId: ids.session, streamEpoch: ids.epoch, frozenSequence: "50", leafId: "deadbeef", lastAppendId: "4096", validation: { newAppendEntries: 1, leafId: "deadbeef" }, publish: true, postFence: [], valid: false, expectedError: "SNAPSHOT_LEAF_CHANGED" },
    { name: "validation-leaf-changed", sessionId: ids.session, streamEpoch: ids.epoch, frozenSequence: "50", leafId: "deadbeef", lastAppendId: "4096", validation: { newAppendEntries: 0, leafId: "cafebabe" }, publish: true, postFence: [], valid: false, expectedError: "SNAPSHOT_LEAF_CHANGED" },
    { name: "post-fence-gap", sessionId: ids.session, streamEpoch: ids.epoch, frozenSequence: "50", leafId: null, lastAppendId: null, validation: { newAppendEntries: 0, leafId: null }, publish: true, postFence: [{ streamEpoch: ids.epoch, sequence: "52" }], valid: false, expectedError: "SEQUENCE_GAP" },
    { name: "publish-before-validation", sessionId: ids.session, streamEpoch: ids.epoch, frozenSequence: "50", leafId: null, lastAppendId: null, publish: true, postFence: [], valid: false, expectedError: "SYNC_REQUIRED" }
  ],
  assistantCases: [
    {
      name: "end-replaces-provisional-authoritatively",
      records: [
        { type: "message_start", message: { role: "assistant", content: [] } },
        { type: "message_update", assistantMessageEvent: { type: "thinking_start", contentIndex: 0, content: "draft" } },
        { type: "message_update", assistantMessageEvent: { type: "thinking_delta", contentIndex: 0, delta: " delta" } },
        { type: "message_update", assistantMessageEvent: { type: "text_start", contentIndex: 1, content: "hello" } },
        { type: "message_update", assistantMessageEvent: { type: "thinking_end", contentIndex: 0, content: "end replacement" } },
        { type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 1, delta: " provisional" } },
        { type: "message_update", assistantMessageEvent: { type: "text_end", contentIndex: 1, content: "end text" } },
        { type: "message_update", assistantMessageEvent: { type: "toolcall_start", contentIndex: 2, toolCall: { id: "tool-1", name: "read", arguments: {} } } },
        { type: "message_update", assistantMessageEvent: { type: "toolcall_end", contentIndex: 2, toolCall: { id: "tool-1", name: "read", arguments: { path: "README.md" } } } },
        { type: "tool_execution_start", toolCallId: "tool-1" },
        { type: "message_end", message: { role: "assistant", content: [{ type: "thinking", thinking: "authoritative", signature: "signed", redacted: true }, { type: "text", text: "final authoritative" }, { type: "toolCall", id: "tool-1", name: "read", arguments: { path: "README.md" } }], usage: { totalTokens: 7 } } }
      ],
      provisionalBeforeEnd: ["end replacement", "end text", { id: "tool-1", name: "read", arguments: { path: "README.md" } }],
      committed: { role: "assistant", content: [{ type: "thinking", thinking: "authoritative", signature: "signed", redacted: true }, { type: "text", text: "final authoritative" }, { type: "toolCall", id: "tool-1", name: "read", arguments: { path: "README.md" } }], usage: { totalTokens: 7 } },
      valid: true
    },
    { name: "delta-before-start-faults", records: [{ type: "message_update", assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "bad" } }], valid: false, recovery: true },
    { name: "unexpected-index-faults", records: [{ type: "message_start", message: { role: "assistant", content: [] } }, { type: "message_update", assistantMessageEvent: { type: "text_start", contentIndex: 1, content: "bad" } }], valid: false, recovery: true },
    { name: "tool-execution-unknown-id-faults", records: [{ type: "message_start", message: { role: "assistant", content: [] } }, { type: "tool_execution_start", toolCallId: "missing" }], valid: false, recovery: true }
  ],
  schemaCases: [
    { name: "pair-registration-options", schema: "messages", valid: true, value: envelope("auth.registration.options", { ceremonyId: ids.ceremony, binding: pairingBinding, publicKey: { rp: { id: "verybigsad.github.io" } } }, 10) },
    { name: "pair-assertion-kind-mismatch", schema: "messages", valid: false, value: envelope("auth.assertion.options", { ceremonyId: ids.ceremony, binding: pairingBinding, publicKey: {} }, 11) },
    { name: "unlock-assertion-options", schema: "messages", valid: true, value: envelope("auth.assertion.options", { ceremonyId: ids.ceremony, binding: unlockBinding, publicKey: { rp: { id: "verybigsad.github.io" } } }, 42) },
    { name: "unlock-missing-device", schema: "messages", valid: false, value: envelope("auth.assertion.options", { ceremonyId: ids.ceremony, binding: { ...unlockBinding, deviceId: undefined }, publicKey: {} }, 43) },
    { name: "unlock-registration-kind", schema: "messages", valid: false, value: envelope("auth.assertion.options", { ceremonyId: ids.ceremony, binding: { ...unlockBinding, ceremonyKind: "registration" }, publicKey: {} }, 44) },
    { name: "pair-begin-response-with-pairing-token", schema: "messages", valid: true, value: envelope("pair.begin", { invitationId: ids.invitation, deviceRouteKeyId: "route-key-1", deviceRoutePublicKey: pairingToken, csrSha256: oneHash, pairingToken }, 13) },
    { name: "pair-begin-response-wrong-length-token", schema: "messages", valid: false, value: envelope("pair.begin", { invitationId: ids.invitation, deviceRouteKeyId: "route-key-1", deviceRoutePublicKey: pairingToken, csrSha256: oneHash, pairingToken: pairingToken.slice(0, 42) }, 14) },
    { name: "pair-begin-response-bad-base64url-token", schema: "messages", valid: false, value: envelope("pair.begin", { invitationId: ids.invitation, deviceRouteKeyId: "route-key-1", deviceRoutePublicKey: pairingToken, csrSha256: oneHash, pairingToken: `${pairingToken.slice(0, 42)}+` }, 15) },
    { name: "approval-offer", schema: "messages", valid: true, value: envelope("approval.offer", { ...approvalBinding, arguments: { command: "rm" }, reasons: ["destructive"], policyVersion: "1", expiresAt: "2026-08-09T12:00:00Z" }, 12) },
    { name: "approval-empty-reasons", schema: "messages", valid: false, value: envelope("approval.offer", { ...approvalBinding, arguments: {}, reasons: [], policyVersion: "1", expiresAt: "2026-08-09T12:00:00Z" }, 13) },
    { name: "prompt-image-stream", schema: "messages", valid: true, value: envelope("stream.open", { streamId: ids.stream, purpose: "prompt_image", mediaType: "image/png", expectedLength: "8388608", sha256: dataDigest, limit: "8388608" }, 14) },
    { name: "prompt-image-over-limit", schema: "messages", valid: false, value: envelope("stream.open", { streamId: ids.stream, purpose: "prompt_image", mediaType: "image/png", expectedLength: "8388609", sha256: dataDigest, limit: "8388609" }, 15) },
    { name: "terminal-history-max", schema: "messages", valid: true, value: envelope("terminal.history.request", { sessionId: ids.session, beforeSequence: "18446744073709551615", limit: 5000 }, 16) },
    { name: "terminal-history-over-lines", schema: "messages", valid: false, value: envelope("terminal.history.request", { sessionId: ids.session, beforeSequence: "0", limit: 5001 }, 17) },
    { name: "dormant-must-be-received", schema: "messages", valid: false, value: envelope("command.state", { commandId: ids.command, state: "ARMED", dormant: true }, 18) },
    { name: "event-uint64-max-inline-raw", schema: "messages", valid: true, value: envelope("event.batch", { events: [{ sessionId: ids.session, streamEpoch: ids.epoch, sequence: "18446744073709551615", piType: "message_update", rawJson: exactRawJson, rawSize: String(Buffer.byteLength(exactRawJson)), rawSha256: sha256(exactRawJson), projection: { type: "message_update", assistantMessageEvent: {} } }] }, 19) },
    { name: "event-uint64-overflow", schema: "messages", valid: false, value: envelope("event.batch", { events: [{ sessionId: ids.session, streamEpoch: ids.epoch, sequence: "18446744073709551616", piType: "message_update", rawJson: exactRawJson, rawSize: String(Buffer.byteLength(exactRawJson)), rawSha256: sha256(exactRawJson), projection: { type: "message_update", assistantMessageEvent: {} } }] }, 20) },
    { name: "snapshot-distinct-append-and-leaf", schema: "messages", valid: true, value: envelope("snapshot.end", { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "4096", leafId: "deadbeef", validated: true }, 21) },
    { name: "snapshot-uppercase-leaf", schema: "messages", valid: false, value: envelope("snapshot.end", { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "4096", leafId: "DEADBEEF", validated: true }, 22) },
    { name: "unknown-additive-message", schema: "messages", valid: true, value: envelope("future.notice", { retained: true }, 23) },
    { name: "route-mac-data-bound-notice", schema: "route", valid: true, value: { type: "route.proof", signed: { audience: "mac-data", routeId: "route-1", keyId: "key-1", rendezvousId: "rv-1", nonce, expiresAt: "2026-08-09T12:00:00Z" }, signature: "AQ" } },
    { name: "route-mac-data-missing-notice", schema: "route", valid: false, value: { type: "route.proof", signed: { audience: "mac-data", routeId: "route-1", keyId: "key-1", nonce, expiresAt: "2026-08-09T12:00:00Z" }, signature: "AQ" } },
    { name: "pairing-invitation", schema: "pairing", valid: true, value: { signed: { version: 1, relayUrl: "wss://relay.example.test/data", routeId: "route-1", routeKeyId: "key-1", invitationId: ids.invitation, macInstanceId: ids.macInstance, expiresAt: "2026-08-09T12:05:00Z", nonce, serverCertificateSha256: zeroHash, directCandidates: [{ host: "192.0.2.1", port: 443 }] }, signature: "AQ" } },
    { name: "pairing-invitation-insecure-relay", schema: "pairing", valid: false, value: { signed: { version: 1, relayUrl: "ws://relay.example.test/data", routeId: "route-1", routeKeyId: "key-1", invitationId: ids.invitation, macInstanceId: ids.macInstance, expiresAt: "2026-08-09T12:05:00Z", nonce, serverCertificateSha256: zeroHash, directCandidates: [] }, signature: "AQ" } },
    { name: "pairing-invitation-missing-mac-instance", schema: "pairing", valid: false, value: { signed: { version: 1, relayUrl: "wss://relay.example.test/data", routeId: "route-1", routeKeyId: "key-1", invitationId: ids.invitation, expiresAt: "2026-08-09T12:05:00Z", nonce, serverCertificateSha256: zeroHash, directCandidates: [] }, signature: "AQ" } },
    { name: "auth-result-success-with-expiry", schema: "messages", valid: true, value: envelope("auth.result", { success: true, expiresAt: "2026-08-09T12:30:00Z" }, 24) },
    { name: "auth-result-failure-code", schema: "messages", valid: true, value: envelope("auth.result", { success: false, error: "AUTH_FAILED" }, 25) },
    { name: "auth-result-missing-success", schema: "messages", valid: false, value: envelope("auth.result", { error: "AUTH_FAILED" }, 26) },
    { name: "auth-result-lowercase-error", schema: "messages", valid: false, value: envelope("auth.result", { success: false, error: "auth_failed" }, 27) },
    { name: "sync-resume-multi-session", schema: "messages", valid: true, value: envelope("sync.resume", { cursors: [sessionCursor, { sessionId: ids.parentSession, streamEpoch: ids.epoch, sequence: "7", leafId: null }] }, 28) },
    { name: "sync-complete-empty", schema: "messages", valid: true, value: envelope("sync.complete", {}, 50) },
    { name: "message-append", schema: "messages", valid: true, value: envelope("message.append", { sessionId: ids.session, streamEpoch: ids.epoch, appendId: "42" }, 29) },
    { name: "message-append-noncanonical-id", schema: "messages", valid: false, value: envelope("message.append", { sessionId: ids.session, streamEpoch: ids.epoch, appendId: "042" }, 30) },
    { name: "session-settled", schema: "messages", valid: true, value: envelope("session.settled", { sessionId: ids.session, settlementId: "settlement-1" }, 31) },
    { name: "session-settled-empty-id", schema: "messages", valid: false, value: envelope("session.settled", { sessionId: ids.session, settlementId: "" }, 32) },
    { name: "session-catalog", schema: "messages", valid: true, value: envelope("session.catalog", { sessions: [catalogEntry] }, 33) },
    { name: "session-catalog-missing-model", schema: "messages", valid: false, value: envelope("session.catalog", { sessions: [{ ...catalogEntry, model: undefined }] }, 34) },
    { name: "snapshot-begin", schema: "messages", valid: true, value: envelope("snapshot.begin", { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "4096" }, 35) },
    { name: "snapshot-begin-hex-append", schema: "messages", valid: false, value: envelope("snapshot.begin", { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "cafebabe" }, 36) },
    { name: "voice-audio", schema: "messages", valid: true, value: envelope("voice.audio", { sessionId: ids.session, chunkSequence: "0", final: false }, 37) },
    { name: "voice-audio-noncanonical-sequence", schema: "messages", valid: false, value: envelope("voice.audio", { sessionId: ids.session, chunkSequence: "01", final: true }, 38) },
    { name: "voice-partial", schema: "messages", valid: true, value: envelope("voice.partial", { sessionId: ids.session, chunkSequence: "3", revision: "2", text: "hello wor" }, 39) },
    { name: "voice-partial-text-overflow", schema: "messages", valid: false, value: envelope("voice.partial", { sessionId: ids.session, chunkSequence: "3", revision: "2", text: "a".repeat(16_385) }, 40) },
    { name: "voice-finish", schema: "messages", valid: true, value: envelope("voice.finish", { sessionId: ids.session, chunkSequence: "5", text: "done" }, 41) },
    { name: "voice-finish-missing-text", schema: "messages", valid: false, value: envelope("voice.finish", { sessionId: ids.session, chunkSequence: "5" }, 42) },
    { name: "push-endpoint-without-wake-key", schema: "messages", valid: true, value: envelope("push.endpoint", { endpointId: ids.device, distributor: "ru.alice", endpoint: "https://push.example.test/ep-1" }, 43) },
    { name: "terminal-history-response", schema: "messages", valid: true, value: envelope("terminal.history.response", { sessionId: ids.session, entries: ["line one", "line two"], truncated: false }, 44) },
    { name: "terminal-history-response-missing-truncated", schema: "messages", valid: false, value: envelope("terminal.history.response", { sessionId: ids.session, entries: [] }, 45) },
    { name: "agents-catalog", schema: "messages", valid: true, value: envelope("agents.catalog", { sessions: [agentsCatalogSession] }, 46) },
    { name: "agents-catalog-description-overflow", schema: "messages", valid: false, value: envelope("agents.catalog", { sessions: [{ ...agentsCatalogSession, agents: [{ ...agent, description: "a".repeat(257) }] }] }, 47) },
    { name: "agents-update-completed", schema: "messages", valid: true, value: envelope("agents.update", { sessionId: ids.session, agent: { ...agent, status: "completed", endedAt: "2026-08-11T05:01:00Z" } }, 48) },
    { name: "agents-update-bad-status", schema: "messages", valid: false, value: envelope("agents.update", { sessionId: ids.session, agent: { ...agent, status: "queued" } }, 49) }
  ],
  wireMessageCases: [
    { name: "auth-result-success", type: "auth.result", body: { success: true, expiresAt: "2026-08-09T12:30:00Z" }, valid: true },
    { name: "auth-result-failure", type: "auth.result", body: { success: false, error: "AUTH_FAILED" }, valid: true },
    { name: "auth-result-minimal", type: "auth.result", body: { success: true }, valid: true },
    { name: "auth-result-missing-success", type: "auth.result", body: { error: "AUTH_FAILED" }, valid: false },
    { name: "auth-result-bad-error-code", type: "auth.result", body: { success: false, error: "auth_failed" }, valid: false },
    { name: "auth-result-bad-expiry", type: "auth.result", body: { success: true, expiresAt: "not-a-date" }, valid: false },
    { name: "sync-resume-multi-session", type: "sync.resume", body: { cursors: [sessionCursor, { sessionId: ids.parentSession, streamEpoch: ids.epoch, sequence: "7", leafId: null }] }, valid: true },
    { name: "sync-complete", type: "sync.complete", body: {}, valid: true },
    { name: "sync-complete-non-empty-body", type: "sync.complete", body: { unexpected: true }, valid: false },
    { name: "sync-resume-noncanonical-sequence", type: "sync.resume", body: { cursors: [{ ...sessionCursor, sequence: "01" }] }, valid: false },
    { name: "message-append", type: "message.append", body: { sessionId: ids.session, streamEpoch: ids.epoch, appendId: "42" }, valid: true },
    { name: "message-append-uint64-max", type: "message.append", body: { sessionId: ids.session, streamEpoch: ids.epoch, appendId: "18446744073709551615", leafId: "deadbeef" }, valid: true },
    { name: "message-append-noncanonical-id", type: "message.append", body: { sessionId: ids.session, streamEpoch: ids.epoch, appendId: "042" }, valid: false },
    { name: "message-append-overflow", type: "message.append", body: { sessionId: ids.session, streamEpoch: ids.epoch, appendId: "18446744073709551616" }, valid: false },
    { name: "session-settled", type: "session.settled", body: { sessionId: ids.session, settlementId: "settlement-1" }, valid: true },
    { name: "session-settled-empty-id", type: "session.settled", body: { sessionId: ids.session, settlementId: "" }, valid: false },
    { name: "session-catalog", type: "session.catalog", body: { sessions: [catalogEntry] }, valid: true },
    { name: "session-catalog-empty", type: "session.catalog", body: { sessions: [] }, valid: true },
    { name: "session-catalog-missing-field", type: "session.catalog", body: { sessions: [{ ...catalogEntry, thinkingLevel: undefined }] }, valid: false },
    { name: "session-catalog-bad-parent", type: "session.catalog", body: { sessions: [{ ...catalogEntry, parentId: "not-a-uuid" }] }, valid: false },
    { name: "snapshot-begin", type: "snapshot.begin", body: { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "4096" }, valid: true },
    { name: "snapshot-begin-empty", type: "snapshot.begin", body: { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "0", lastAppendId: null }, valid: true },
    { name: "snapshot-begin-hex-append", type: "snapshot.begin", body: { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "cafebabe" }, valid: false },
    { name: "snapshot-end", type: "snapshot.end", body: { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "4096", leafId: "deadbeef", validated: true }, valid: true },
    { name: "snapshot-end-null-leaf", type: "snapshot.end", body: { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "0", lastAppendId: null, leafId: null, validated: true }, valid: true },
    { name: "snapshot-end-not-validated", type: "snapshot.end", body: { sessionId: ids.session, streamEpoch: ids.epoch, messageCount: "4096", lastAppendId: "4096", leafId: "deadbeef", validated: false }, valid: false },
    { name: "voice-audio", type: "voice.audio", body: { sessionId: ids.session, chunkSequence: "0", final: false }, valid: true },
    { name: "voice-audio-final-max", type: "voice.audio", body: { sessionId: ids.session, chunkSequence: "18446744073709551615", final: true }, valid: true },
    { name: "voice-audio-missing-final", type: "voice.audio", body: { sessionId: ids.session, chunkSequence: "0" }, valid: false },
    { name: "voice-audio-noncanonical-sequence", type: "voice.audio", body: { sessionId: ids.session, chunkSequence: "01", final: false }, valid: false },
    { name: "voice-partial", type: "voice.partial", body: { sessionId: ids.session, chunkSequence: "3", revision: "2", text: "hello wor" }, valid: true },
    { name: "voice-partial-missing-revision", type: "voice.partial", body: { sessionId: ids.session, chunkSequence: "3", text: "hello wor" }, valid: false },
    { name: "voice-partial-text-overflow", type: "voice.partial", body: { sessionId: ids.session, chunkSequence: "3", revision: "2", text: "a".repeat(16_385) }, valid: false, expectedError: "FRAME_TOO_LARGE" },
    { name: "voice-finish", type: "voice.finish", body: { sessionId: ids.session, chunkSequence: "5", text: "done" }, valid: true },
    { name: "voice-finish-missing-text", type: "voice.finish", body: { sessionId: ids.session, chunkSequence: "5" }, valid: false },
    { name: "push-endpoint-without-wake-key", type: "push.endpoint", body: { endpointId: ids.device, distributor: "ru.alice", endpoint: "https://push.example.test/ep-1" }, valid: true },
    { name: "push-endpoint-with-wake-key", type: "push.endpoint", body: { endpointId: ids.device, distributor: "ru.alice", endpoint: "https://push.example.test/ep-1", wakePublicKey: nonce }, valid: true },
    { name: "push-endpoint-bad-wake-key", type: "push.endpoint", body: { endpointId: ids.device, distributor: "ru.alice", endpoint: "https://push.example.test/ep-1", wakePublicKey: "not base64url!!!" }, valid: false },
    { name: "terminal-history-request", type: "terminal.history.request", body: { sessionId: ids.session, beforeSequence: "5000", limit: 500 }, valid: true },
    { name: "terminal-history-request-latest", type: "terminal.history.request", body: { sessionId: ids.session, beforeSequence: null, limit: 1 }, valid: true },
    { name: "terminal-history-request-over-limit", type: "terminal.history.request", body: { sessionId: ids.session, beforeSequence: "5000", limit: 5001 }, valid: false },
    { name: "terminal-history-request-bad-sequence", type: "terminal.history.request", body: { sessionId: ids.session, beforeSequence: "-1", limit: 500 }, valid: false },
    { name: "terminal-history-response", type: "terminal.history.response", body: { sessionId: ids.session, entries: ["line one", "line two"], truncated: false }, valid: true },
    { name: "terminal-history-response-truncated", type: "terminal.history.response", body: { sessionId: ids.session, entries: [], truncated: true }, valid: true },
    { name: "terminal-history-response-missing-truncated", type: "terminal.history.response", body: { sessionId: ids.session, entries: [] }, valid: false },
    { name: "agents-catalog", type: "agents.catalog", body: { sessions: [agentsCatalogSession] }, valid: true },
    { name: "agents-catalog-empty", type: "agents.catalog", body: { sessions: [] }, valid: true },
    { name: "agents-catalog-agent-bound", type: "agents.catalog", body: { sessions: [{ sessionId: ids.session, agents: Array.from({ length: 256 }, (_, index) => ({ ...agent, agentId: `agent-${index}` })) }] }, valid: true },
    { name: "agents-catalog-over-bound", type: "agents.catalog", body: { sessions: [{ sessionId: ids.session, agents: Array.from({ length: 257 }, (_, index) => ({ ...agent, agentId: `agent-${index}` })) }] }, valid: false },
    { name: "agents-catalog-description-overflow", type: "agents.catalog", body: { sessions: [{ ...agentsCatalogSession, agents: [{ ...agent, description: "a".repeat(257) }] }] }, valid: false },
    { name: "agents-update-completed", type: "agents.update", body: { sessionId: ids.session, agent: { ...agent, status: "completed", endedAt: "2026-08-11T05:01:00Z" } }, valid: true },
    { name: "agents-update-missing-agent", type: "agents.update", body: { sessionId: ids.session }, valid: false },
    { name: "agents-update-bad-status", type: "agents.update", body: { sessionId: ids.session, agent: { ...agent, status: "queued" } }, valid: false }
  ]
};

await writeFile(fixturePath, `${JSON.stringify(corpus, null, 2)}\n`);
