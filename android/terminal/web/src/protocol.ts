export const TERMINAL_PREFIX_BYTES = 16;
export const MAX_TERMINAL_DATA_BYTES = 1_048_560;
export const MAX_HISTORY_BYTES = 1_048_576;
export const MAX_HISTORY_LINES = 5_000;
export const MAX_CONTROL_MESSAGE_CHARACTERS = 1_500_000;
export const UINT64_MAX = 18_446_744_073_709_551_615n;

export interface TerminalPacket {
  readonly generation: bigint;
  readonly sequence: bigint;
  readonly bytes: Uint8Array;
}

export type NativeCommand =
  | { readonly type: "terminal.generation"; readonly generation: bigint; readonly connected: boolean; readonly arrayBufferBridge: boolean }
  | { readonly type: "terminal.output"; readonly generation: bigint; readonly sequence: bigint; readonly bytes: Uint8Array }
  | { readonly type: "terminal.connection"; readonly connected: boolean }
  | { readonly type: "terminal.paste"; readonly text: string }
  | { readonly type: "terminal.key"; readonly data: string }
  | { readonly type: "terminal.focus" }
  | { readonly type: "terminal.history"; readonly generation: bigint; readonly capturedAt: string; readonly text: string; readonly truncatedLines: boolean; readonly truncatedBytes: boolean }
  | { readonly type: "terminal.history.close" }
  | { readonly type: "terminal.restored"; readonly requiresReconnect: true; readonly screenRestored: false; readonly scrollbackRestored: false };

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function utf8Size(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function isRfc3339(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) && !Number.isNaN(Date.parse(value));
}

function lineCount(value: string): number {
  if (value.length === 0) return 0;
  let lines = value.endsWith("\n") ? 0 : 1;
  for (const character of value) if (character === "\n") lines += 1;
  return lines;
}

export function parseUint64(value: unknown): bigint | undefined {
  if (typeof value !== "string" || !/^(0|[1-9][0-9]{0,19})$/.test(value)) return undefined;
  const parsed = BigInt(value);
  return parsed <= UINT64_MAX ? parsed : undefined;
}

export function incrementUint64(value: bigint): bigint | undefined {
  return value < UINT64_MAX ? value + 1n : undefined;
}

export function encodeTerminalPacket(generation: bigint, sequence: bigint, bytes: Uint8Array): ArrayBuffer {
  if (generation < 0n || generation > UINT64_MAX || sequence < 0n || sequence > UINT64_MAX || bytes.byteLength > MAX_TERMINAL_DATA_BYTES) {
    throw new RangeError("terminal packet is out of bounds");
  }
  const packet = new Uint8Array(TERMINAL_PREFIX_BYTES + bytes.byteLength);
  const view = new DataView(packet.buffer);
  view.setBigUint64(0, generation, false);
  view.setBigUint64(8, sequence, false);
  packet.set(bytes, TERMINAL_PREFIX_BYTES);
  return packet.buffer;
}

export function decodeTerminalPacket(value: ArrayBuffer): TerminalPacket | undefined {
  if (value.byteLength < TERMINAL_PREFIX_BYTES || value.byteLength - TERMINAL_PREFIX_BYTES > MAX_TERMINAL_DATA_BYTES) return undefined;
  const view = new DataView(value);
  return {
    generation: view.getBigUint64(0, false),
    sequence: view.getBigUint64(8, false),
    bytes: new Uint8Array(value.slice(TERMINAL_PREFIX_BYTES)),
  };
}

export function encodeBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.byteLength; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.byteLength)));
  }
  return btoa(binary);
}

export function decodeBase64(value: unknown): Uint8Array | undefined {
  if (typeof value !== "string" || value.length > 1_398_080 || value.length % 4 !== 0 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) return undefined;
  let binary: string;
  try {
    binary = atob(value);
  } catch {
    return undefined;
  }
  if (binary.length > MAX_TERMINAL_DATA_BYTES) return undefined;
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

export function parseNativeCommand(serialized: string): NativeCommand | undefined {
  if (serialized.length > MAX_CONTROL_MESSAGE_CHARACTERS) return undefined;
  let parsed: unknown;
  try {
    parsed = JSON.parse(serialized);
  } catch {
    return undefined;
  }
  if (!isRecord(parsed) || typeof parsed.type !== "string") return undefined;
  switch (parsed.type) {
    case "terminal.generation": {
      const generation = parseUint64(parsed.generation);
      if (generation === undefined || typeof parsed.connected !== "boolean" || typeof parsed.arrayBufferBridge !== "boolean") return undefined;
      return { type: parsed.type, generation, connected: parsed.connected, arrayBufferBridge: parsed.arrayBufferBridge };
    }
    case "terminal.output": {
      const generation = parseUint64(parsed.generation);
      const sequence = parseUint64(parsed.sequence);
      const bytes = decodeBase64(parsed.bytes);
      if (generation === undefined || sequence === undefined || bytes === undefined) return undefined;
      return { type: parsed.type, generation, sequence, bytes };
    }
    case "terminal.connection":
      return typeof parsed.connected === "boolean" ? { type: parsed.type, connected: parsed.connected } : undefined;
    case "terminal.paste":
      return typeof parsed.text === "string" && utf8Size(parsed.text) <= MAX_HISTORY_BYTES ? { type: parsed.type, text: parsed.text } : undefined;
    case "terminal.key":
      return typeof parsed.data === "string" && utf8Size(parsed.data) <= 4_096 ? { type: parsed.type, data: parsed.data } : undefined;
    case "terminal.focus":
    case "terminal.history.close":
      return { type: parsed.type };
    case "terminal.history": {
      const generation = parseUint64(parsed.generation);
      if (generation === undefined ||
          typeof parsed.capturedAt !== "string" || parsed.capturedAt.length > 128 || !isRfc3339(parsed.capturedAt) ||
          typeof parsed.text !== "string" || utf8Size(parsed.text) > MAX_HISTORY_BYTES || lineCount(parsed.text) > MAX_HISTORY_LINES ||
          typeof parsed.truncatedLines !== "boolean" || typeof parsed.truncatedBytes !== "boolean") return undefined;
      return {
        type: parsed.type,
        generation,
        capturedAt: parsed.capturedAt,
        text: parsed.text,
        truncatedLines: parsed.truncatedLines,
        truncatedBytes: parsed.truncatedBytes,
      };
    }
    case "terminal.restored":
      return parsed.requiresReconnect === true && parsed.screenRestored === false && parsed.scrollbackRestored === false
        ? { type: parsed.type, requiresReconnect: true, screenRestored: false, scrollbackRestored: false }
        : undefined;
    default:
      return undefined;
  }
}
