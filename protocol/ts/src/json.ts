import { ProtocolError } from "./errors.js";

export type JsonPrimitive = boolean | null | number | string;
export type JsonArray = JsonValue[];
export type JsonObject = { [key: string]: JsonValue };
export type JsonValue = JsonArray | JsonObject | JsonPrimitive;

export function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function assertJsonValue(value: unknown): asserts value is JsonValue {
  const pending: { value: unknown; exiting: boolean }[] = [{ value, exiting: false }];
  const ancestors = new WeakSet<object>();
  while (pending.length > 0) {
    const frame = pending.pop()!;
    const current = frame.value;
    if (frame.exiting) {
      ancestors.delete(current as object);
      continue;
    }
    if (
      current === null ||
      typeof current === "boolean" ||
      typeof current === "string"
    ) {
      if (typeof current === "string" && !hasOnlyUnicodeScalars(current)) {
        throw new ProtocolError("PROTOCOL_VIOLATION", "JSON contains an unpaired surrogate");
      }
      continue;
    }
    if (typeof current === "number") {
      if (!Number.isFinite(current)) {
        throw new ProtocolError("PROTOCOL_VIOLATION", "JSON number must be finite");
      }
      continue;
    }
    if (typeof current !== "object" || current === undefined) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "Value is not JSON-compatible");
    }
    if (ancestors.has(current)) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "JSON value must not contain cycles");
    }
    ancestors.add(current);
    pending.push({ value: current, exiting: true });
    if (Array.isArray(current)) {
      for (const item of current) {
        pending.push({ value: item, exiting: false });
      }
      continue;
    }
    for (const [key, item] of Object.entries(current)) {
      if (!hasOnlyUnicodeScalars(key)) {
        throw new ProtocolError("PROTOCOL_VIOLATION", "JSON key contains an unpaired surrogate");
      }
      pending.push({ value: item, exiting: false });
    }
  }
}

export function decodeUtf8Strict(bytes: Uint8Array): string {
  try {
    return new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Payload is not valid UTF-8");
  }
}

export function parseJsonObject(text: string): JsonObject {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Payload is not valid JSON");
  }
  assertJsonValue(value);
  if (!isJsonObject(value)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "JSON payload must be an object");
  }
  return value;
}

function hasOnlyUnicodeScalars(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0xd800 || code > 0xdfff) {
      continue;
    }
    if (code > 0xdbff || index + 1 >= value.length) {
      return false;
    }
    const next = value.charCodeAt(index + 1);
    if (next < 0xdc00 || next > 0xdfff) {
      return false;
    }
    index += 1;
  }
  return true;
}
