import { createHash } from "node:crypto";
import { PROTOCOL_MAJOR, PROTOCOL_MINOR } from "@pimobile/protocol";
import type { PiJsonRecord } from "./lf-json-framer.js";

export const MAX_INLINE_RAW_BYTES = 128 * 1024;
export const MAX_ESCAPED_JSON_FRAME_BYTES = 256 * 1024;
export const MAX_PROJECTION_BYTES = 128 * 1024;
const PROTOCOL_FRAME_HEADER_BYTES = 12;
const MAX_PROJECTED_STRING_BYTES = 64 * 1024;
const MAX_PROJECTED_ARRAY_ITEMS = 128;
const MAX_PROJECTED_OBJECT_KEYS = 128;
const MAX_PROJECTED_DEPTH = 10;
const MAX_PROJECTED_NODES = 4096;

export type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

export interface BoundedProjection {
  readonly value: Readonly<Record<string, JsonValue>>;
  readonly complete: boolean;
}

export interface ProjectedPiRecord {
  readonly piType: string;
  readonly rawBytes: Buffer;
  readonly rawJson: string;
  readonly rawSize: string;
  readonly rawSha256: string;
  readonly projection: BoundedProjection;
}

export interface PiEventEnvelopeContext {
  readonly sessionId: string;
  readonly streamEpoch: string;
  readonly sequence: string;
  readonly messageId: string;
  readonly rawRefStreamId: string;
}

export interface PreparedPiEvent {
  readonly mode: "inline" | "reference";
  readonly event: Readonly<Record<string, unknown>>;
  readonly envelope: Readonly<Record<string, unknown>>;
  readonly jsonPayload: Buffer;
  readonly escapedFrameBytes: number;
  readonly projected: ProjectedPiRecord;
}

interface ProjectionBudget {
  nodes: number;
  complete: boolean;
}

const ROOT_FIELDS: Readonly<Record<string, readonly string[]>> = {
  agent_end: ["messages", "willRetry"],
  agent_settled: [],
  agent_start: [],
  auto_retry_end: ["attempt", "finalError", "success"],
  auto_retry_start: ["attempt", "delayMs", "errorMessage", "maxAttempts"],
  bash_execution_update: ["delta", "id"],
  compaction_end: ["aborted", "errorMessage", "reason", "result", "willRetry"],
  compaction_start: ["reason"],
  extension_error: ["error", "event", "extensionPath"],
  extension_ui_request: [
    "id",
    "method",
    "message",
    "notifyType",
    "options",
    "placeholder",
    "prefill",
    "statusKey",
    "statusText",
    "text",
    "timeout",
    "title",
    "widgetKey",
    "widgetLines",
    "widgetPlacement",
  ],
  message_end: ["message"],
  message_start: ["message"],
  message_update: ["assistantMessageEvent"],
  queue_update: ["followUp", "steering"],
  response: ["command", "data", "error", "id", "success"],
  summarization_retry_attempt_start: ["reason", "source"],
  summarization_retry_finished: [],
  summarization_retry_scheduled: ["attempt", "delayMs", "errorMessage", "maxAttempts"],
  tool_execution_end: ["isError", "result", "toolCallId", "toolName"],
  tool_execution_start: ["args", "toolCallId", "toolName"],
  tool_execution_update: ["args", "partialResult", "toolCallId", "toolName"],
  turn_end: ["message", "toolResults"],
  turn_start: [],
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasValidUnicode(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (next < 0xdc00 || next > 0xdfff) {
        return false;
      }
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      return false;
    }
  }
  return true;
}

function cloneBounded(value: unknown, depth: number, budget: ProjectionBudget): JsonValue | undefined {
  budget.nodes += 1;
  if (budget.nodes > MAX_PROJECTED_NODES || depth > MAX_PROJECTED_DEPTH) {
    budget.complete = false;
    return undefined;
  }

  if (value === null || typeof value === "boolean") {
    return value;
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      budget.complete = false;
      return undefined;
    }
    return value;
  }
  if (typeof value === "string") {
    if (!hasValidUnicode(value) || Buffer.byteLength(value, "utf8") > MAX_PROJECTED_STRING_BYTES) {
      budget.complete = false;
      return undefined;
    }
    return value;
  }
  if (Array.isArray(value)) {
    if (value.length > MAX_PROJECTED_ARRAY_ITEMS) {
      budget.complete = false;
    }
    const result: JsonValue[] = [];
    for (const item of value.slice(0, MAX_PROJECTED_ARRAY_ITEMS)) {
      const cloned = cloneBounded(item, depth + 1, budget);
      if (cloned === undefined) {
        budget.complete = false;
        return undefined;
      }
      result.push(cloned);
    }
    return result;
  }
  if (!isRecord(value)) {
    budget.complete = false;
    return undefined;
  }

  const keys = Object.keys(value).sort();
  if (keys.length > MAX_PROJECTED_OBJECT_KEYS) {
    budget.complete = false;
  }
  const result: Record<string, JsonValue> = {};
  for (const key of keys.slice(0, MAX_PROJECTED_OBJECT_KEYS)) {
    const cloned = cloneBounded(value[key], depth + 1, budget);
    if (cloned === undefined) {
      budget.complete = false;
      continue;
    }
    result[key] = cloned;
  }
  return result;
}

function safePiType(value: unknown): string {
  if (typeof value !== "string" || Buffer.byteLength(value, "utf8") > 256 || !hasValidUnicode(value)) {
    return "unknown";
  }
  return value;
}

/** Produces the reducer-only deterministic subset; exact unknown data remains solely in raw bytes. */
export function projectPiRecord(record: PiJsonRecord): ProjectedPiRecord {
  if (!Buffer.from(record.rawJson, "utf8").equals(record.rawBytes)) {
    throw new TypeError("rawJson does not byte-match rawBytes");
  }

  const piType = safePiType(record.value["type"]);
  const fields = ROOT_FIELDS[piType] ?? [];
  const budget: ProjectionBudget = { nodes: 0, complete: true };
  const projection: Record<string, JsonValue> = { type: piType };
  const addedFields: string[] = [];

  for (const field of fields) {
    if (!(field in record.value)) {
      continue;
    }
    const cloned = cloneBounded(record.value[field], 1, budget);
    if (cloned !== undefined) {
      projection[field] = cloned;
      addedFields.push(field);
    }
  }

  let bounded = projection;
  while (Buffer.byteLength(JSON.stringify(bounded), "utf8") > MAX_PROJECTION_BYTES && addedFields.length > 0) {
    const field = addedFields.pop();
    if (field !== undefined) {
      const { [field]: _dropped, ...remaining } = bounded;
      void _dropped;
      bounded = remaining;
      budget.complete = false;
    }
  }
  if (Buffer.byteLength(JSON.stringify(bounded), "utf8") > MAX_PROJECTION_BYTES) {
    throw new RangeError("bounded projection invariant failed");
  }

  return {
    piType,
    rawBytes: Buffer.from(record.rawBytes),
    rawJson: record.rawJson,
    rawSize: String(record.rawBytes.length),
    rawSha256: createHash("sha256").update(record.rawBytes).digest("hex"),
    projection: { value: bounded, complete: budget.complete },
  };
}

function makeEnvelope(messageId: string, event: Readonly<Record<string, unknown>>): Readonly<Record<string, unknown>> {
  return {
    v: { major: PROTOCOL_MAJOR, minor: PROTOCOL_MINOR },
    type: "event.batch",
    messageId,
    replyTo: null,
    body: { events: [event] },
  };
}

function serializeEnvelope(envelope: Readonly<Record<string, unknown>>): { payload: Buffer; frameBytes: number } {
  const payload = Buffer.from(JSON.stringify(envelope), "utf8");
  return { payload, frameBytes: PROTOCOL_FRAME_HEADER_BYTES + payload.length };
}

/** Makes the exact inline-vs-reference decision against raw and fully escaped frame limits. */
export function preparePiEvent(record: PiJsonRecord, context: PiEventEnvelopeContext): PreparedPiEvent {
  if (!/^(0|[1-9][0-9]*)$/.test(context.sequence)) {
    throw new TypeError("sequence must be unsigned decimal text");
  }

  const projected = projectPiRecord(record);
  const base = {
    sessionId: context.sessionId,
    streamEpoch: context.streamEpoch,
    sequence: context.sequence,
    piType: projected.piType,
    rawSize: projected.rawSize,
    rawSha256: projected.rawSha256,
    projection: projected.projection.value,
  };

  if (projected.rawBytes.length <= MAX_INLINE_RAW_BYTES) {
    const event = { ...base, rawJson: projected.rawJson };
    const envelope = makeEnvelope(context.messageId, event);
    const serialized = serializeEnvelope(envelope);
    if (serialized.frameBytes <= MAX_ESCAPED_JSON_FRAME_BYTES) {
      return {
        mode: "inline",
        event,
        envelope,
        jsonPayload: serialized.payload,
        escapedFrameBytes: serialized.frameBytes,
        projected,
      };
    }
  }

  const event = {
    ...base,
    rawRef: {
      streamId: context.rawRefStreamId,
      size: projected.rawSize,
      sha256: projected.rawSha256,
      mediaType: "application/json",
    },
  };
  const envelope = makeEnvelope(context.messageId, event);
  const serialized = serializeEnvelope(envelope);
  if (serialized.frameBytes > MAX_ESCAPED_JSON_FRAME_BYTES) {
    throw new RangeError("referenced Pi event exceeds the JSON frame limit");
  }
  return {
    mode: "reference",
    event,
    envelope,
    jsonPayload: serialized.payload,
    escapedFrameBytes: serialized.frameBytes,
    projected,
  };
}
