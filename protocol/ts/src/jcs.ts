import { createHash } from "node:crypto";
import canonicalize from "canonicalize";
import { ProtocolError } from "./errors.js";

const LEAF_ID_PATTERN = /^(?:[0-9a-f]{8})$/;
import type { JsonObject, JsonValue } from "./json.js";
import { assertJsonValue } from "./json.js";

export interface CommandHashInput {
  readonly sessionId: string;
  readonly operation: string;
  readonly payload: JsonObject;
  readonly expectedLeafId?: null | string;
}

export function canonicalizeJson(value: JsonValue): string {
  assertJsonValue(value);
  const result = canonicalize(value);
  if (result === undefined) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "JSON value cannot be canonicalized");
  }
  return result;
}

export function sha256Hex(bytes: Uint8Array | string): string {
  return createHash("sha256").update(bytes).digest("hex");
}

export function commandPayloadHash(input: CommandHashInput): string {
  const hashInput: JsonObject = {
    sessionId: input.sessionId,
    operation: input.operation,
    payload: input.payload
  };
  if (Object.hasOwn(input, "expectedLeafId")) {
    if (input.expectedLeafId === undefined || (input.expectedLeafId !== null && !LEAF_ID_PATTERN.test(input.expectedLeafId))) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "expectedLeafId must be absent, null, or eight lowercase hex characters");
    }
    hashInput["expectedLeafId"] = input.expectedLeafId;
  }
  return sha256Hex(canonicalizeJson(hashInput));
}
