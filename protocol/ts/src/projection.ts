import { MAX_INLINE_RAW_BYTES } from "./constants.js";
import { ProtocolError } from "./errors.js";
import type { JsonObject, JsonValue } from "./json.js";
import { assertJsonValue, isJsonObject } from "./json.js";

const PROJECTED_KEYS = [
  "type",
  "assistantMessageEvent",
  "message",
  "toolCallId",
  "requestId",
  "willRetry",
  "attempt",
  "reason"
] as const;

export function projectPiRecord(record: JsonObject): JsonObject {
  assertJsonValue(record);
  const projection: JsonObject = {};
  for (const key of PROJECTED_KEYS) {
    const value: JsonValue | undefined = record[key];
    if (value !== undefined) {
      projection[key] = value;
    }
  }
  const encoded = JSON.stringify(projection);
  if (encoded === undefined || Buffer.byteLength(encoded, "utf8") > MAX_INLINE_RAW_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "Pi reducer projection exceeds its bound");
  }
  return projection;
}

export function isProjectionOf(record: JsonObject, projection: JsonObject): boolean {
  const expected = projectPiRecord(record);
  return jsonEquals(expected, projection);
}

function jsonEquals(left: JsonValue, right: JsonValue): boolean {
  if (left === right) {
    return true;
  }
  if (Array.isArray(left) || Array.isArray(right)) {
    if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) {
      return false;
    }
    return left.every((value, index) => {
      const other = right[index];
      return other !== undefined && jsonEquals(value, other);
    });
  }
  if (!isJsonObject(left) || !isJsonObject(right)) {
    return false;
  }
  const leftKeys = Object.keys(left);
  const rightKeys = Object.keys(right);
  if (leftKeys.length !== rightKeys.length) {
    return false;
  }
  return leftKeys.every((key) => {
    const leftValue = left[key];
    const rightValue = right[key];
    return leftValue !== undefined && rightValue !== undefined && jsonEquals(leftValue, rightValue);
  });
}
