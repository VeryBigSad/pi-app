import type { JsonObject } from "@pimobile/protocol";
import type {
  TerminalChannel,
  TerminalOpenResult,
  TerminalOutput,
  TerminalRuntime,
} from "../gateway/types.js";
import { StreamGatewayError } from "../gateway/streams.js";
import type { TerminalAttachment, TerminalBackend, TerminalSession } from "../terminal/backend.js";
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
  private readonly attachments = new Map<string, { session: TerminalSession; attachment: TerminalAttachment }>();

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
    this.attachments.set(sessionId, { session, attachment });
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
      close: async (reason) => {
        void reason;
        this.attachments.delete(sessionId);
        await attachment.detach();
      },
    };
    return {
      generation,
      channel,
      body: { sequence: "0", columns: cols, rows: rowCount },
    };
  }

  async history(request: JsonObject, signal: AbortSignal): Promise<JsonObject> {
    signal.throwIfAborted();
    const sessionId = request["sessionId"];
    const generationRaw = request["terminalGeneration"];
    if (typeof sessionId !== "string" || typeof generationRaw !== "string") {
      throw new StreamGatewayError("PROTOCOL_VIOLATION", "terminal.history.request is invalid");
    }
    const entry = this.attachments.get(sessionId);
    if (entry === undefined) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "terminal is not attached");
    const result = await entry.session.history({
      terminalGeneration: BigInt(generationRaw),
      maxLines: boundedLimit(request["maxLines"], MAX_TERMINAL_HISTORY_LINES),
      maxBytes: boundedLimit(request["maxBytes"], MAX_TERMINAL_HISTORY_BYTES),
    });
    return {
      terminalGeneration: result.terminalGeneration.toString(),
      capturedAt: result.capturedAt,
      text: result.text,
      truncated: result.truncatedLines || result.truncatedBytes,
      truncatedLines: result.truncatedLines,
      truncatedBytes: result.truncatedBytes,
    };
  }
}

function boundedLimit(value: unknown, maximum: number): number {
  if (value === undefined) return maximum;
  if (!Number.isSafeInteger(value)) throw new StreamGatewayError("PROTOCOL_VIOLATION", "terminal.history.request limit is invalid");
  return Math.min(Math.max(value as number, 1), maximum);
}
