import { MAX_INLINE_RAW_BYTES } from "./constants.js";
import { ProtocolError } from "./errors.js";
import type { JsonObject } from "./json.js";
import { decodeUtf8Strict, parseJsonObject } from "./json.js";
import { projectPiRecord } from "./projection.js";
import { sha256Hex } from "./jcs.js";

export interface InlineRawRecord {
  readonly rawJson: string;
  readonly rawSize: string;
  readonly rawSha256: string;
  readonly projection: JsonObject;
}

export function projectRawPiJson(rawBytes: Uint8Array): InlineRawRecord {
  if (rawBytes.length > MAX_INLINE_RAW_BYTES) {
    throw new ProtocolError("FRAME_TOO_LARGE", "Inline Pi record exceeds its bound");
  }
  const rawJson = decodeUtf8Strict(rawBytes);
  const parsed = parseJsonObject(rawJson);
  return { rawJson, rawSize: rawBytes.length.toString(10), rawSha256: sha256Hex(rawBytes), projection: projectPiRecord(parsed) };
}

export function verifyInlineRawRecord(record: InlineRawRecord): void {
  const bytes = new TextEncoder().encode(record.rawJson);
  if (bytes.length > MAX_INLINE_RAW_BYTES || record.rawSize !== bytes.length.toString(10) || record.rawSha256 !== sha256Hex(bytes)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Inline Pi record metadata is invalid");
  }
  const parsed = parseJsonObject(record.rawJson);
  const expected = projectPiRecord(parsed);
  if (JSON.stringify(expected) !== JSON.stringify(record.projection)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Inline Pi record projection is invalid");
  }
}
