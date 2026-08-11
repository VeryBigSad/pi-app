import { MAX_UINT64 } from "./constants.js";
import { ProtocolError } from "./errors.js";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const UUID_V4_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UINT64_PATTERN = /^(0|[1-9][0-9]{0,19})$/;
const LEAF_ID_PATTERN = /^[0-9a-f]{8}$/;

export function parseUint64(value: string): bigint {
  if (!UINT64_PATTERN.test(value)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "uint64 must be canonical decimal text");
  }
  const parsed = BigInt(value);
  if (parsed > MAX_UINT64) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "uint64 exceeds its maximum");
  }
  return parsed;
}

export function formatUint64(value: bigint): string {
  if (value < 0n || value > MAX_UINT64) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "uint64 is out of range");
  }
  return value.toString(10);
}

export function assertLeafId(value: string | null): void {
  if (value !== null && !LEAF_ID_PATTERN.test(value)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Leaf ID must be null or eight lowercase hex characters");
  }
}

export function uuidToBytes(uuid: string): Uint8Array {
  if (!UUID_PATTERN.test(uuid)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "UUID must be lowercase canonical text");
  }
  return Uint8Array.from(Buffer.from(uuid.replaceAll("-", ""), "hex"));
}

export function uuidV4ToBytes(uuid: string): Uint8Array {
  if (!UUID_V4_PATTERN.test(uuid)) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "UUID must be a lowercase UUIDv4");
  }
  return uuidToBytes(uuid);
}

export function bytesToUuid(bytes: Uint8Array): string {
  if (bytes.length !== 16) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "UUID prefix must contain exactly 16 bytes");
  }
  const hex = Buffer.from(bytes).toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function assertUuidV4(uuid: string): void {
  uuidV4ToBytes(uuid);
}

export function readUint64BigEndian(bytes: Uint8Array, offset = 0): bigint {
  if (offset < 0 || offset + 8 > bytes.length) {
    throw new ProtocolError("PROTOCOL_VIOLATION", "Truncated uint64");
  }
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getBigUint64(offset, false);
}

export function writeUint64BigEndian(value: bigint): Uint8Array {
  formatUint64(value);
  const output = new Uint8Array(8);
  new DataView(output.buffer).setBigUint64(0, value, false);
  return output;
}
