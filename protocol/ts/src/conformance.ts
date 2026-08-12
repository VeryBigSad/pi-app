import { createHash } from "node:crypto";
import {
  MAX_AGENTS,
  MAX_BINARY_DATA_BYTES,
  MAX_PROMPT_IMAGE_BYTES,
  MAX_TERMINAL_HISTORY_BYTES,
  MAX_TERMINAL_HISTORY_LINES,
  MAX_VOICE_BODY_BYTES,
  MAX_VOICE_TEXT_CHARS,
} from "./constants.js";
import { parseUint64 } from "./binary.js";
import { ProtocolError } from "./errors.js";
import type { JsonObject, JsonValue } from "./json.js";
import { assertJsonValue, isJsonObject } from "./json.js";

const SHA256_PATTERN = /^[0-9a-f]{64}$/;
const LEAF_PATTERN = /^[0-9a-f]{8}$/;

export class ContiguousStream {
  private nextSequence = 0;
  private nextOffset = 0n;
  private readonly digest = createHash("sha256");
  private ended = false;

  constructor(
    readonly streamId: string,
    readonly limit: bigint,
    private readonly expectedLength?: bigint,
    private readonly expectedSha256?: string,
  ) {
    if (limit < 0n || expectedLength !== undefined && (expectedLength < 0n || expectedLength > limit)) this.fail();
    if (expectedSha256 !== undefined && !SHA256_PATTERN.test(expectedSha256)) this.fail();
  }

  accept(streamId: string, sequence: number, offset: bigint, data: Uint8Array): void {
    if (
      this.ended || streamId !== this.streamId || sequence !== this.nextSequence || offset !== this.nextOffset ||
      !Number.isInteger(sequence) || sequence < 0 || sequence > 0xffff_ffff || data.length > MAX_BINARY_DATA_BYTES ||
      this.nextOffset + BigInt(data.length) > this.limit
    ) this.fail();
    this.digest.update(data);
    this.nextSequence += 1;
    this.nextOffset += BigInt(data.length);
  }

  close(length: bigint, sha256: string): void {
    if (this.ended || length !== this.nextOffset || this.expectedLength !== undefined && length !== this.expectedLength || !SHA256_PATTERN.test(sha256)) this.fail();
    const actual = this.digest.digest("hex");
    if (actual !== sha256 || this.expectedSha256 !== undefined && actual !== this.expectedSha256) this.fail();
    this.ended = true;
  }

  get length(): bigint {
    return this.nextOffset;
  }

  private fail(): never {
    this.ended = true;
    throw new ProtocolError("STREAM_INVALID", "Stream ordering, bound, or digest is invalid");
  }
}

export type CursorResult = "applied" | "duplicate";

export class RecoveryCursor {
  private sequence: bigint;

  constructor(readonly streamEpoch: string, sequence: string) {
    this.sequence = parseUint64(sequence);
  }

  accept(streamEpoch: string, sequence: string): CursorResult {
    if (streamEpoch !== this.streamEpoch) throw new ProtocolError("SYNC_REQUIRED", "Stream epoch changed");
    const incoming = parseUint64(sequence);
    if (incoming <= this.sequence) return "duplicate";
    if (incoming !== this.sequence + 1n) throw new ProtocolError("SEQUENCE_GAP", "Event sequence is not contiguous");
    this.sequence = incoming;
    return "applied";
  }

  snapshot(leafId: string | null): { streamEpoch: string; sequence: string; leafId: string | null } {
    if (leafId !== null && !LEAF_PATTERN.test(leafId)) throw new ProtocolError("PROTOCOL_VIOLATION", "Leaf ID is invalid");
    return { streamEpoch: this.streamEpoch, sequence: this.sequence.toString(), leafId };
  }
}

export class SnapshotAttempt {
  private readonly postFence: RecoveryCursor;
  private validated = false;
  private published = false;

  constructor(
    readonly sessionId: string,
    readonly streamEpoch: string,
    readonly frozenSequence: string,
    readonly leafId: string | null,
    readonly lastAppendId: string | null,
  ) {
    parseUint64(frozenSequence);
    if (leafId !== null && !LEAF_PATTERN.test(leafId)) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "Snapshot leaf or append cursor is invalid");
    }
    if (lastAppendId !== null) parseUint64(lastAppendId);
    this.postFence = new RecoveryCursor(streamEpoch, frozenSequence);
  }

  acceptAdjunct(streamEpoch: string, sequence: string): void {
    parseUint64(sequence);
    if (streamEpoch !== this.streamEpoch || sequence !== this.frozenSequence) {
      throw new ProtocolError("SYNC_REQUIRED", "Snapshot adjunct is not tagged with the frozen cursor");
    }
  }

  validate(newAppendEntries: number, leafId: string | null): void {
    if (!Number.isInteger(newAppendEntries) || newAppendEntries < 0 || leafId !== null && !LEAF_PATTERN.test(leafId)) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "Snapshot validation result is invalid");
    }
    if (newAppendEntries !== 0 || leafId !== this.leafId) {
      throw new ProtocolError("SNAPSHOT_LEAF_CHANGED", "Snapshot changed during validation");
    }
    this.validated = true;
  }

  publish(): void {
    if (!this.validated) throw new ProtocolError("SYNC_REQUIRED", "Snapshot cannot publish before validation");
    this.published = true;
  }

  acceptPostFence(streamEpoch: string, sequence: string): CursorResult {
    if (!this.published) throw new ProtocolError("SYNC_REQUIRED", "Post-fence replay cannot start before publication");
    return this.postFence.accept(streamEpoch, sequence);
  }
}

export interface PairingBinding {
  readonly ceremonyKind: "registration" | "assertion";
  readonly invitationId: string;
  readonly sessionBinding: string;
  readonly csrSha256: string;
  readonly rpId: string;
  readonly origin: string;
  readonly challenge: string;
  readonly expiresAt: string;
}

export function assertPairingBinding(expected: PairingBinding, actual: PairingBinding): void {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(actual.invitationId) || !SHA256_PATTERN.test(actual.sessionBinding) || !SHA256_PATTERN.test(actual.csrSha256) || !/^[A-Za-z0-9_-]{43}$/u.test(actual.challenge) || !["registration", "assertion"].includes(actual.ceremonyKind) || actual.rpId.length === 0 || actual.origin.length === 0 || !Number.isFinite(Date.parse(actual.expiresAt))) {
    throw new ProtocolError("AUTH_FAILED", "Pairing binding is malformed");
  }
  for (const key of Object.keys(expected) as (keyof PairingBinding)[]) {
    if (expected[key] !== actual[key]) throw new ProtocolError("AUTH_FAILED", `Pairing binding mismatch: ${key}`);
  }
}

export interface UnlockBinding {
  readonly ceremonyKind: string;
  readonly deviceId: string;
  readonly rpId: string;
  readonly origin: string;
  readonly challenge: string;
  readonly expiresAt: string;
}

export function assertUnlockBinding(expected: UnlockBinding, actual: UnlockBinding): void {
  if (actual.ceremonyKind !== "assertion" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u.test(actual.deviceId) || !/^[A-Za-z0-9_-]{43}$/u.test(actual.challenge) || actual.rpId.length === 0 || actual.origin.length === 0 || !Number.isFinite(Date.parse(actual.expiresAt))) {
    throw new ProtocolError("AUTH_FAILED", "Unlock binding is malformed");
  }
  for (const key of Object.keys(expected) as (keyof UnlockBinding)[]) {
    if (expected[key] !== actual[key]) throw new ProtocolError("AUTH_FAILED", `Unlock binding mismatch: ${key}`);
  }
}

export function assertPairingToken(pairingToken: string, sessionBinding: string): void {
  if (!/^[A-Za-z0-9_-]{43}$/u.test(pairingToken) || !SHA256_PATTERN.test(sessionBinding)) {
    throw new ProtocolError("AUTH_FAILED", "Pairing token is malformed");
  }
  const tokenBytes = Buffer.from(pairingToken, "base64url");
  if (tokenBytes.length !== 32 || createHash("sha256").update(tokenBytes).digest("hex") !== sessionBinding) {
    throw new ProtocolError("AUTH_FAILED", "Pairing token does not match the session binding");
  }
}

export interface ApprovalBinding {
  readonly offerId: string;
  readonly operationId: string;
  readonly argumentHash: string;
}

export function assertApprovalBinding(expected: ApprovalBinding, actual: ApprovalBinding): void {
  if (expected.offerId !== actual.offerId || expected.operationId !== actual.operationId || expected.argumentHash !== actual.argumentHash || !SHA256_PATTERN.test(actual.argumentHash)) {
    throw new ProtocolError("APPROVAL_DENIED", "Approval tuple is stale or mismatched");
  }
}

export class ApprovalOffer {
  private consumed = false;

  constructor(readonly binding: ApprovalBinding, readonly expiresAtEpochMilliseconds: number) {
    if (!Number.isSafeInteger(expiresAtEpochMilliseconds) || expiresAtEpochMilliseconds < 0) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "Approval expiry is invalid");
    }
  }

  decide(binding: ApprovalBinding, nowEpochMilliseconds: number): void {
    if (!Number.isSafeInteger(nowEpochMilliseconds) || nowEpochMilliseconds < 0) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "Approval decision time is invalid");
    }
    if (this.consumed || nowEpochMilliseconds >= this.expiresAtEpochMilliseconds) {
      this.consumed = true;
      throw new ProtocolError("APPROVAL_EXPIRED", "Approval is expired or already consumed");
    }
    this.consumed = true;
    assertApprovalBinding(this.binding, binding);
  }

  expire(): void {
    this.consumed = true;
  }
}

export interface ReadyBlob {
  readonly blobId: string;
  readonly ownerDeviceId: string;
  readonly size: string;
  readonly sha256: string;
  readonly mimeType: string;
  readonly expiresAtEpochMilliseconds: number;
  readonly ready: boolean;
  readonly referenced: boolean;
}

export interface ImageRef {
  readonly blobId: string;
  readonly size: string;
  readonly sha256: string;
  readonly mimeType: string;
}

export function assertPromptImageRef(blob: ReadyBlob, ref: ImageRef, deviceId: string, nowEpochMilliseconds: number): void {
  let size: bigint;
  try {
    size = parseUint64(ref.size);
  } catch {
    throw new ProtocolError("BLOB_INVALID", "Prompt image size is invalid");
  }
  if (!Number.isSafeInteger(nowEpochMilliseconds) || !Number.isSafeInteger(blob.expiresAtEpochMilliseconds) || !blob.ready || blob.referenced || blob.ownerDeviceId !== deviceId || blob.expiresAtEpochMilliseconds <= nowEpochMilliseconds) {
    throw new ProtocolError("BLOB_NOT_READY", "Prompt image is unavailable");
  }
  if (
    size > BigInt(MAX_PROMPT_IMAGE_BYTES) || ref.blobId !== blob.blobId || ref.size !== blob.size ||
    ref.sha256 !== blob.sha256 || ref.mimeType !== blob.mimeType || !SHA256_PATTERN.test(ref.sha256) ||
    !["image/jpeg", "image/png", "image/webp"].includes(ref.mimeType)
  ) throw new ProtocolError("BLOB_INVALID", "Prompt image reference is invalid");
}

export function assertTerminalHistory(text: string, maxLines: number, maxBytes: number): void {
  if (!Number.isInteger(maxLines) || maxLines < 1 || maxLines > MAX_TERMINAL_HISTORY_LINES || !Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > MAX_TERMINAL_HISTORY_BYTES) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Terminal history request is out of bounds");
  }
  const lines = text.length === 0 ? 0 : (text.match(/\n/gu)?.length ?? 0) + (text.endsWith("\n") ? 0 : 1);
  if (lines > maxLines || Buffer.byteLength(text, "utf8") > maxBytes) throw new ProtocolError("FRAME_TOO_LARGE", "Terminal history result exceeds its request");
}

const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const OPAQUE_ID_PATTERN = /^[A-Za-z0-9._:-]+$/u;
const ERROR_CODE_PATTERN = /^[A-Z][A-Z0-9_]+$/u;
const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/u;
const MAX_CATALOG_SESSIONS = 512;
const MAX_SYNC_CURSORS = 512;
const MAX_HISTORY_ENTRIES = MAX_TERMINAL_HISTORY_LINES;

function violation(message: string): never {
  throw new ProtocolError("PROTOCOL_VIOLATION", message);
}

function isUuidV4(value: unknown): value is string {
  return typeof value === "string" && UUID_V4_PATTERN.test(value);
}

function isUint64Text(value: unknown): value is string {
  if (typeof value !== "string") return false;
  try {
    parseUint64(value);
    return true;
  } catch {
    return false;
  }
}

function isUint64OrNull(value: unknown): value is string | null {
  return value === null || isUint64Text(value);
}

function isDateTime(value: unknown): value is string {
  return typeof value === "string" && value.length <= 64 && Number.isFinite(Date.parse(value));
}

function isBoundedString(value: unknown, maxLength: number): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= maxLength;
}

function isLeaf(value: unknown): boolean {
  return value === null || typeof value === "string" && LEAF_PATTERN.test(value);
}

function assertCursor(value: unknown): void {
  if (!isJsonObject(value) || !isUuidV4(value["sessionId"]) || !isUuidV4(value["streamEpoch"]) || !isUint64Text(value["sequence"]) || !isLeaf(value["leafId"])) {
    violation("Sync cursor is invalid");
  }
}

function assertVoiceBody(body: JsonObject): void {
  if (Buffer.byteLength(JSON.stringify(body), "utf8") > MAX_VOICE_BODY_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "Voice message body exceeds its bound");
  }
}

function assertVoiceText(value: unknown): void {
  if (typeof value !== "string") violation("Voice text must be a string");
  if ((value as string).length > MAX_VOICE_TEXT_CHARS) {
    throw new ProtocolError("FRAME_TOO_LARGE", "Voice transcript text exceeds its character bound");
  }
}

function assertAgent(value: unknown): void {
  if (!isJsonObject(value)) violation("Agent must be an object");
  const agent = value as JsonObject;
  if (
    !isBoundedString(agent["agentId"], 128) || !OPAQUE_ID_PATTERN.test(agent["agentId"] as string) ||
    !(agent["parentAgentId"] === undefined || isBoundedString(agent["parentAgentId"], 128) && OPAQUE_ID_PATTERN.test(agent["parentAgentId"] as string)) ||
    !isBoundedString(agent["description"], 256) || !isBoundedString(agent["agentType"], 128) ||
    !["running", "waiting", "completed", "failed", "stopped"].includes(agent["status"] as string) || !isDateTime(agent["startedAt"]) ||
    !(agent["endedAt"] === undefined || isDateTime(agent["endedAt"])) ||
    !(agent["toolUses"] === undefined || Number.isInteger(agent["toolUses"]) && (agent["toolUses"] as number) >= 0 && (agent["toolUses"] as number) <= 2_147_483_647) ||
    !(agent["model"] === undefined || isBoundedString(agent["model"], 128))
  ) violation("Agent is invalid");
}

function assertAgentsCatalogSession(value: unknown): void {
  if (!isJsonObject(value) || !isUuidV4(value["sessionId"]) || !Array.isArray(value["agents"]) || value["agents"].length > MAX_AGENTS) {
    violation("agents.catalog session is invalid");
  }
  value["agents"].forEach(assertAgent);
}

function assertSessionCatalogEntry(value: unknown): void {
  if (!isJsonObject(value)) violation("Session catalog entry must be an object");
  const entry = value as JsonObject;
  if (
    !isUuidV4(entry["sessionId"]) || !isBoundedString(entry["provider"], 64) || !isBoundedString(entry["model"], 128) ||
    !isBoundedString(entry["thinkingLevel"], 32) || !isBoundedString(entry["repo"], 4096) ||
    !(entry["worktree"] === null || isBoundedString(entry["worktree"], 4096)) || !isBoundedString(entry["cwd"], 4096) ||
    !(entry["parentId"] === null || isUuidV4(entry["parentId"])) || !isDateTime(entry["createdAt"]) || !isDateTime(entry["updatedAt"])
  ) violation("Session catalog entry is invalid");
}

/** Validates frozen v1 wire-message bodies shared verbatim between the TypeScript and Kotlin conformance suites. */
export function assertWireMessage(type: string, body: JsonObject): void {
  switch (type) {
    case "auth.result": {
      if (typeof body["success"] !== "boolean") violation("auth.result requires a boolean success");
      const error = body["error"];
      if (error !== undefined && (typeof error !== "string" || !ERROR_CODE_PATTERN.test(error) || error.length > 64)) {
        violation("auth.result error must be an upper snake case code");
      }
      if (body["expiresAt"] !== undefined && !isDateTime(body["expiresAt"])) violation("auth.result expiresAt must be a date-time");
      return;
    }
    case "sync.complete": {
      if (Object.keys(body).length !== 0) violation("sync.complete requires an empty body");
      return;
    }
    case "sync.resume": {
      const cursors = body["cursors"];
      if (!Array.isArray(cursors) || cursors.length > MAX_SYNC_CURSORS) violation("sync.resume requires a bounded cursors array");
      cursors.forEach(assertCursor);
      return;
    }
    case "message.append": {
      if (!isUuidV4(body["sessionId"]) || !isUuidV4(body["streamEpoch"]) || !isUint64Text(body["appendId"])) {
        violation("message.append requires sessionId, streamEpoch, and a canonical uint64 appendId");
      }
      if (body["leafId"] !== undefined && !isLeaf(body["leafId"])) violation("message.append leafId is invalid");
      return;
    }
    case "session.settled": {
      if (!isUuidV4(body["sessionId"]) || !isBoundedString(body["settlementId"], 128) || !OPAQUE_ID_PATTERN.test(body["settlementId"] as string)) {
        violation("session.settled requires sessionId and an opaque settlementId");
      }
      return;
    }
    case "session.catalog": {
      const sessions = body["sessions"];
      if (!Array.isArray(sessions) || sessions.length > MAX_CATALOG_SESSIONS) violation("session.catalog requires a bounded sessions array");
      sessions.forEach(assertSessionCatalogEntry);
      return;
    }
    case "agents.catalog": {
      const sessions = body["sessions"];
      if (!Array.isArray(sessions) || sessions.length > MAX_CATALOG_SESSIONS) violation("agents.catalog requires a bounded sessions array");
      sessions.forEach(assertAgentsCatalogSession);
      return;
    }
    case "agents.update": {
      if (!isUuidV4(body["sessionId"])) violation("agents.update requires sessionId");
      assertAgent(body["agent"]);
      return;
    }
    case "snapshot.begin": {
      if (!isUuidV4(body["sessionId"]) || !isUuidV4(body["streamEpoch"]) || !isUint64Text(body["messageCount"]) || !isUint64OrNull(body["lastAppendId"])) {
        violation("snapshot.begin requires sessionId, streamEpoch, messageCount, and lastAppendId");
      }
      return;
    }
    case "snapshot.end": {
      if (
        !isUuidV4(body["sessionId"]) || !isUuidV4(body["streamEpoch"]) || !isUint64Text(body["messageCount"]) ||
        !isUint64OrNull(body["lastAppendId"]) || !isLeaf(body["leafId"]) || body["validated"] !== true
      ) violation("snapshot.end requires sessionId, streamEpoch, messageCount, lastAppendId, leafId, and validated");
      return;
    }
    case "voice.audio": {
      if (!isUuidV4(body["sessionId"]) || !isUint64Text(body["chunkSequence"]) || typeof body["final"] !== "boolean") {
        violation("voice.audio requires sessionId, chunkSequence, and final");
      }
      assertVoiceBody(body);
      return;
    }
    case "voice.partial": {
      if (!isUuidV4(body["sessionId"]) || !isUint64Text(body["chunkSequence"]) || !isUint64Text(body["revision"])) {
        violation("voice.partial requires sessionId, chunkSequence, and revision");
      }
      assertVoiceText(body["text"]);
      assertVoiceBody(body);
      return;
    }
    case "voice.finish": {
      if (!isUuidV4(body["sessionId"]) || !isUint64Text(body["chunkSequence"])) violation("voice.finish requires sessionId and chunkSequence");
      assertVoiceText(body["text"]);
      assertVoiceBody(body);
      return;
    }
    case "push.endpoint": {
      if (!isUuidV4(body["endpointId"]) || !isBoundedString(body["distributor"], 128) || !isBoundedString(body["endpoint"], 4096)) {
        violation("push.endpoint requires endpointId, distributor, and endpoint");
      }
      const wakePublicKey = body["wakePublicKey"];
      if (wakePublicKey !== undefined && (typeof wakePublicKey !== "string" || !BASE64URL_PATTERN.test(wakePublicKey))) {
        violation("push.endpoint wakePublicKey must be base64url when present");
      }
      return;
    }
    case "terminal.history.request": {
      const limit = body["limit"];
      if (
        !isUuidV4(body["sessionId"]) || !isUint64OrNull(body["beforeSequence"]) ||
        !Number.isInteger(limit) || (limit as number) < 1 || (limit as number) > MAX_TERMINAL_HISTORY_LINES
      ) violation("terminal.history.request requires sessionId, beforeSequence, and a bounded limit");
      return;
    }
    case "terminal.history.response": {
      const entries = body["entries"];
      if (
        !isUuidV4(body["sessionId"]) || !Array.isArray(entries) || entries.length > MAX_HISTORY_ENTRIES ||
        entries.some((entry) => typeof entry !== "string") || typeof body["truncated"] !== "boolean"
 ) violation("terminal.history.response requires sessionId, string entries, and truncated");
      if (Buffer.byteLength(JSON.stringify(body), "utf8") > MAX_TERMINAL_HISTORY_BYTES) {
        throw new ProtocolError("FRAME_TOO_LARGE", "terminal.history.response exceeds its byte bound");
      }
      return;
    }
    default:
      violation(`No shared wire validator for ${type}`);
  }
}

type Block = { kind: "text" | "thinking" | "toolCall"; open: boolean; value: JsonValue };

export class AssistantMessageAssembler {
  private blocks: Block[] | undefined;
  private committedValue: JsonObject | undefined;
  private recoveryNeeded = false;

  apply(record: JsonObject): void {
    assertJsonValue(record);
    const type = stringField(record, "type");
    if (type === "message_start") {
      if (this.blocks !== undefined) return this.fault();
      const message = objectField(record, "message");
      const content = message["content"];
      if (!Array.isArray(content) || content.length !== 0) return this.fault();
      this.blocks = [];
      return;
    }
    if (type === "message_end") {
      if (this.blocks === undefined || this.blocks.some((block) => block.open)) return this.fault();
      this.committedValue = objectField(record, "message");
      this.blocks = undefined;
      return;
    }
    if (type === "tool_execution_start" || type === "tool_execution_update" || type === "tool_execution_end") {
      if (this.blocks === undefined || !this.blocks.some((block) => block.kind === "toolCall" && isJsonObject(block.value) && block.value["id"] === record["toolCallId"])) return this.fault();
      return;
    }
    if (type !== "message_update" || this.blocks === undefined) return this.fault();
    const event = objectField(record, "assistantMessageEvent");
    const eventType = stringField(event, "type");
    const index = integerField(event, "contentIndex");
    if (eventType.endsWith("_start")) {
      if (index !== this.blocks.length) return this.fault();
      if (eventType === "text_start" || eventType === "thinking_start") {
        this.blocks.push({ kind: eventType === "text_start" ? "text" : "thinking", open: true, value: stringField(event, "content") });
        return;
      }
      if (eventType === "toolcall_start") {
        this.blocks.push({ kind: "toolCall", open: true, value: objectField(event, "toolCall") });
        return;
      }
      return this.fault();
    }
    const block = this.blocks[index];
    if (block === undefined || !block.open) return this.fault();
    if (eventType === "text_delta" || eventType === "thinking_delta") {
      const kind = eventType === "text_delta" ? "text" : "thinking";
      if (block.kind !== kind || typeof block.value !== "string") return this.fault();
      block.value += stringField(event, "delta");
      return;
    }
    if (eventType === "toolcall_delta") {
      if (block.kind !== "toolCall") return this.fault();
      block.value = objectField(event, "toolCall");
      return;
    }
    if (eventType === "text_end" || eventType === "thinking_end") {
      const kind = eventType === "text_end" ? "text" : "thinking";
      if (block.kind !== kind) return this.fault();
      block.value = stringField(event, "content");
      block.open = false;
      return;
    }
    if (eventType === "toolcall_end") {
      if (block.kind !== "toolCall") return this.fault();
      block.value = objectField(event, "toolCall");
      block.open = false;
      return;
    }
    return this.fault();
  }

  provisional(): JsonValue[] | undefined {
    return this.blocks?.map((block) => structuredClone(block.value));
  }

  committed(): JsonObject | undefined {
    return this.committedValue === undefined ? undefined : structuredClone(this.committedValue);
  }

  needsRecovery(): boolean {
    return this.recoveryNeeded;
  }

  transportFault(): void {
    this.fault();
  }

  private fault(): never {
    this.blocks = undefined;
    this.recoveryNeeded = true;
    throw new ProtocolError("SYNC_REQUIRED", "Assistant delta transition is invalid");
  }
}

function stringField(value: JsonObject, key: string): string {
  const field = value[key];
  if (typeof field !== "string") throw new ProtocolError("PROTOCOL_VIOLATION", `${key} must be a string`);
  return field;
}

function integerField(value: JsonObject, key: string): number {
  const field = value[key];
  if (!Number.isSafeInteger(field) || (field as number) < 0) throw new ProtocolError("PROTOCOL_VIOLATION", `${key} must be a nonnegative safe integer`);
  return field as number;
}

function objectField(value: JsonObject, key: string): JsonObject {
  const field = value[key];
  if (!isJsonObject(field)) throw new ProtocolError("PROTOCOL_VIOLATION", `${key} must be an object`);
  return field;
}
