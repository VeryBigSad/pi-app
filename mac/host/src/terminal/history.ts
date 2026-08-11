import type { TerminalHistoryRequest, TerminalHistoryResult, TmuxProcessFactory } from "./types.js";
import {
  MAX_TERMINAL_HISTORY_BYTES,
  MAX_TERMINAL_HISTORY_LINES,
  TerminalError,
} from "./types.js";

export async function captureTerminalHistory(
  tmux: TmuxProcessFactory,
  prefix: readonly string[],
  target: string,
  request: TerminalHistoryRequest,
  now: () => Date,
): Promise<TerminalHistoryResult> {
  validateHistoryRequest(request);
  const result = await tmux.run([
    ...prefix,
    "capture-pane",
    "-p",
    "-e",
    "-J",
    "-S",
    `-${String(request.maxLines + 1)}`,
    "-t",
    target,
  ], {
    timeoutMs: 3_000,
    captureBytes: request.maxBytes + 4,
    captureMode: "tail",
  });
  if (result.exitCode !== 0) throw new TerminalError("TERMINAL_HISTORY_FAILED");
  const truncatedBytes = result.stdoutBytes > request.maxBytes;
  let bytes: Uint8Array = Buffer.from(result.stdout);
  if (bytes.byteLength > request.maxBytes) bytes = validUtf8Tail(bytes, request.maxBytes);
  let text: string;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new TerminalError("TERMINAL_HISTORY_FAILED");
  }
  if (text.endsWith("\n")) text = text.slice(0, -1);
  const lines = text.length === 0 ? [] : text.split("\n");
  const truncatedLines = result.stdoutLines > request.maxLines;
  if (lines.length > request.maxLines) text = lines.slice(lines.length - request.maxLines).join("\n");
  while (Buffer.byteLength(text, "utf8") > request.maxBytes) text = dropFirstCodePoint(text);
  return {
    terminalGeneration: request.terminalGeneration,
    capturedAt: now().toISOString(),
    text,
    truncatedLines,
    truncatedBytes,
  };
}

function validateHistoryRequest(request: TerminalHistoryRequest): void {
  if (
    request.terminalGeneration < 0n
    || !Number.isSafeInteger(request.maxLines)
    || request.maxLines < 1
    || request.maxLines > MAX_TERMINAL_HISTORY_LINES
    || !Number.isSafeInteger(request.maxBytes)
    || request.maxBytes < 1
    || request.maxBytes > MAX_TERMINAL_HISTORY_BYTES
  ) throw new TerminalError("TERMINAL_INVALID_ARGUMENT");
}

function validUtf8Tail(bytes: Uint8Array, maximum: number): Uint8Array {
  let start = Math.max(0, bytes.byteLength - maximum);
  while (start < bytes.byteLength) {
    const byte = bytes[start];
    if (byte === undefined || (byte & 0xc0) !== 0x80) break;
    start += 1;
  }
  return Uint8Array.from(bytes.subarray(start));
}

function dropFirstCodePoint(text: string): string {
  const point = text.codePointAt(0);
  if (point === undefined) return "";
  return text.slice(point > 0xffff ? 2 : 1);
}
