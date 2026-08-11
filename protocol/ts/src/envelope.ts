import { PROTOCOL_MAJOR, PROTOCOL_MINOR } from "./constants.js";
import { assertUuidV4 } from "./binary.js";
import { ProtocolError } from "./errors.js";
import type { JsonObject } from "./json.js";
import { assertJsonValue, isJsonObject } from "./json.js";

const TYPE_PATTERN = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$/;

export interface Envelope extends JsonObject {
  readonly v: { readonly major: number; readonly minor: number };
  readonly type: string;
  readonly messageId: string;
  readonly replyTo: string | null;
  readonly body: JsonObject;
}

export function assertEnvelope(value: unknown): asserts value is Envelope {
  assertJsonValue(value);
  if (!isJsonObject(value)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Envelope must be an object");
  }
  const version = value["v"];
  if (!isJsonObject(version) || version["major"] !== PROTOCOL_MAJOR || !Number.isInteger(version["minor"]) || (version["minor"] as number) < 0 || (version["minor"] as number) > 255) {
    throw new ProtocolError("UNSUPPORTED_VERSION", "Envelope version is unsupported");
  }
  const type = value["type"];
  if (typeof type !== "string" || type.length === 0 || type.length > 64 || !TYPE_PATTERN.test(type)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Envelope type is invalid");
  }
  const messageId = value["messageId"];
  if (typeof messageId !== "string") {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Envelope messageId is invalid");
  }
  assertUuidV4(messageId);
  const replyTo = value["replyTo"];
  if (replyTo !== null && typeof replyTo !== "string") {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Envelope replyTo is invalid");
  }
  if (typeof replyTo === "string") {
    assertUuidV4(replyTo);
  }
  if (!isJsonObject(value["body"])) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Envelope body must be an object");
  }
}

export function createEnvelope(type: string, messageId: string, replyTo: string | null, body: JsonObject): Envelope {
  const value: JsonObject = { v: { major: PROTOCOL_MAJOR, minor: PROTOCOL_MINOR }, type, messageId, replyTo, body };
  assertEnvelope(value);
  return value;
}
