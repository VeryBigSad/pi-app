import { MAX_JSON_PAYLOAD_BYTES, type JsonObject } from "@pimobile/protocol";
import type {
  TerminalChannel,
  TerminalOpenResult,
  TerminalOutput,
  TerminalRuntime,
} from "../gateway/types.js";
import { StreamGatewayError } from "../gateway/streams.js";
import type { TerminalBackend, TerminalSession } from "../terminal/backend.js";
import { MAX_TERMINAL_HISTORY_BYTES, MAX_TERMINAL_HISTORY_LINES } from "../terminal/types.js";

export interface TerminalRuntimeAdapterOptions {
  readonly backend: TerminalBackend;
  readonly command: string;
  readonly args: readonly string[];
  readonly cwdForSession: (sessionId: string) => string;
}

/**
 * Bridges the gateway terminal stream contract to the private-tmux/node-pty
 * backend. The gateway owns wire sequencing; the backend owns PTY ordering.
 */
export class TerminalRuntimeAdapter implements TerminalRuntime {
  constructor(private readonly options: TerminalRuntimeAdapterOptions) {}

  async open(request: JsonObject, output: TerminalOutput, signal: AbortSignal): Promise<TerminalOpenResult> {
    const sessionId = request["sessionId"];
    const columns = request["columns"];
    const rows = request["rows"];
    if (
      typeof sessionId !== "string"
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(sessionId)
      || !Number.isInteger(columns) || !Number.isInteger(rows)
    ) {
      throw new StreamGatewayError("PROTOCOL_VIOLATION", "terminal.open is invalid");
    }
    const cols = columns as number;
    const rowCount = rows as number;
    const session = this.options.backend.session(sessionId) ?? await this.options.backend.createSession({
      sessionId,
      command: this.options.command,
      args: this.options.args,
      cwd: this.options.cwdForSession(sessionId),
      columns: cols,
      rows: rowCount,
    });
    const attachment = await session.attach({
      columns: cols,
      rows: rowCount,
      onOutput: (terminalOutput) => output.write(terminalOutput.bytes, signal),
      onMarker: (marker) => {
        if (marker.type === "reset_required") return output.reset(marker.reason, signal);
        return undefined;
      },
    });
    const generation = attachment.terminalGeneration;
    let inboundSequence = 0n;
    const channel: TerminalChannel = {
      write: async (data, writeSignal) => {
        writeSignal.throwIfAborted();
        const sequence = inboundSequence;
        inboundSequence += 1n;
        await attachment.send({ terminalGeneration: generation, sequence, bytes: data });
      },
      resize: async (newColumns, newRows, resizeSignal) => {
        resizeSignal.throwIfAborted();
        await attachment.resize(generation, newColumns, newRows);
      },
      reset: async (reason, resetSignal) => {
        resetSignal.throwIfAborted();
        await output.reset(reason, resetSignal);
      },
      history: async (request, historySignal) => await terminalHistory(
        sessionId,
        session,
        generation,
        request,
        historySignal,
      ),
      close: async (reason) => {
        void reason;
        await attachment.detach();
      },
    };
    return {
      generation,
      channel,
      body: { sequence: "0", columns: cols, rows: rowCount },
    };
  }

}

async function terminalHistory(
  sessionId: string,
  session: TerminalSession,
  generation: bigint,
  request: JsonObject,
  signal: AbortSignal,
): Promise<JsonObject> {
  signal.throwIfAborted();
  const requestedSessionId = request["sessionId"];
  const generationRaw = request["terminalGeneration"];
  if (
    requestedSessionId !== sessionId || !UUID_V4.test(sessionId)
    || typeof generationRaw !== "string" || !UINT64_DECIMAL.test(generationRaw)
    || BigInt(generationRaw) > UINT64_MAX || BigInt(generationRaw) !== generation
  ) {
    throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "terminal history capability is stale");
  }
  const result = await session.history({
    terminalGeneration: generation,
    maxLines: requiredBound(request["maxLines"], MAX_TERMINAL_HISTORY_LINES),
    maxBytes: requiredBound(request["maxBytes"], MAX_TERMINAL_HISTORY_BYTES),
  });
  if (result.terminalGeneration !== generation) {
    throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "terminal history generation changed");
  }
  return boundResponse({
    sessionId,
    terminalGeneration: result.terminalGeneration.toString(),
    capturedAt: result.capturedAt,
    text: result.text,
    truncatedLines: result.truncatedLines,
    truncatedBytes: result.truncatedBytes,
  });
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const UINT64_DECIMAL = /^(0|[1-9][0-9]{0,19})$/u;
const UINT64_MAX = 18_446_744_073_709_551_615n;

function requiredBound(value: unknown, maximum: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < 1 || (value as number) > maximum) {
    throw new StreamGatewayError("PROTOCOL_VIOLATION", "terminal.history.request bound is invalid");
  }
  return value as number;
}

function boundResponse(response: {
  sessionId: string;
  terminalGeneration: string;
  capturedAt: string;
  text: string;
  truncatedLines: boolean;
  truncatedBytes: boolean;
}): JsonObject {
  let text = response.text;
  let truncatedBytes = response.truncatedBytes;
  if (jsonBytes(response, text, truncatedBytes) > MAX_JSON_PAYLOAD_BYTES) {
    let low = 0;
    let high = text.length;
    while (low < high) {
      let middle = Math.floor((low + high) / 2);
      if (middle > 0 && isLowSurrogate(text.charCodeAt(middle))) middle += 1;
      if (jsonBytes(response, text.slice(middle), true) <= MAX_JSON_PAYLOAD_BYTES) high = middle;
      else low = middle + 1;
    }
    if (low > 0 && isLowSurrogate(text.charCodeAt(low))) low += 1;
    text = text.slice(low);
    truncatedBytes = true;
  }
  if (jsonBytes(response, text, truncatedBytes) > MAX_JSON_PAYLOAD_BYTES) {
    throw new StreamGatewayError("PROTOCOL_VIOLATION", "terminal.history.response exceeds JSON bounds");
  }
  return { ...response, text, truncatedBytes };
}

function jsonBytes(response: {
  sessionId: string;
  terminalGeneration: string;
  capturedAt: string;
  text: string;
  truncatedLines: boolean;
  truncatedBytes: boolean;
}, text: string, truncatedBytes: boolean): number {
  const body = { ...response, text, truncatedBytes };
  return Buffer.byteLength(JSON.stringify({
    v: { major: 1, minor: 0 },
    type: "terminal.history.response",
    messageId: "00000000-0000-4000-8000-000000000000",
    replyTo: null,
    body,
  }), "utf8");
}

function isLowSurrogate(value: number): boolean {
  return value >= 0xdc00 && value <= 0xdfff;
}
