import {
  FrameKind,
  MAX_BINARY_DATA_BYTES,
  decodeStreamPayload,
  decodeTerminalPayload,
  encodeStreamPayload,
  encodeTerminalPayload,
  type JsonObject,
} from "@pimobile/protocol";
import type {
  BlobOutput,
  BlobRuntime,
  BlobStreamMetadata,
  BlobStreamUpload,
  OutboundMessage,
  TerminalChannel,
  TerminalOutput,
  TerminalRuntime,
} from "./types.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const UINT64 = /^(0|[1-9][0-9]{0,19})$/;
const MAX_UINT64 = 18_446_744_073_709_551_615n;
const MAX_PROMPT_IMAGE_BYTES = 8n * 1024n * 1024n;

export class StreamGatewayError extends Error {
  constructor(readonly code: "PROTOCOL_VIOLATION" | "STREAM_INVALID" | "TERMINAL_RESET_REQUIRED", message: string) {
    super(message);
    this.name = "StreamGatewayError";
  }
}

export type BinarySend = (kind: FrameKind, payload: Uint8Array, signal: AbortSignal) => Promise<void>;
export type JsonSend = (message: OutboundMessage) => Promise<void>;

interface OpenBlob {
  readonly metadata: BlobStreamMetadata;
  readonly upload: BlobStreamUpload;
  readonly controller: AbortController;
  sequence: number;
  offset: bigint;
}

interface OpenTerminal {
  readonly generation: bigint;
  readonly channel: TerminalChannel;
  readonly controller: AbortController;
  inboundSequence: bigint;
  outboundSequence: bigint;
  outboundTail: Promise<void>;
}

export class ContentStreamManager {
  private readonly blobs = new Map<string, OpenBlob>();
  private terminal: OpenTerminal | undefined;

  constructor(
    private readonly blobRuntime: BlobRuntime,
    private readonly terminalRuntime: TerminalRuntime,
    private readonly sendBinary: BinarySend,
    private readonly sendJson: JsonSend,
    private readonly connectionSignal: AbortSignal,
  ) {}

  async openBlob(body: JsonObject): Promise<void> {
    const metadata = parseBlobMetadata(body);
    if (this.blobs.has(metadata.streamId)) throw new StreamGatewayError("STREAM_INVALID", "Stream is already open");
    const controller = linkedController(this.connectionSignal);
    const output: BlobOutput = {
      write: async (streamId, sequence, offset, data, signal) => {
        signal.throwIfAborted();
        if (!UUID_V4.test(streamId)) throw new StreamGatewayError("STREAM_INVALID", "Outbound stream identity is invalid");
        await this.sendBinary(FrameKind.BlobChunk, encodeStreamPayload({ streamId, sequence, offset, data }), controller.signal);
      },
    };
    const upload = await this.blobRuntime.open(metadata, output, controller.signal);
    this.blobs.set(metadata.streamId, { metadata, upload, controller, sequence: 0, offset: 0n });
  }

  async blobChunk(payload: Uint8Array): Promise<void> {
    const chunk = decodeStreamPayload(payload);
    const stream = this.blobs.get(chunk.streamId);
    if (stream === undefined) {
      await this.sendJson({ type: "stream.error", body: { streamId: chunk.streamId, code: "STREAM_INVALID" } });
      throw new StreamGatewayError("STREAM_INVALID", "Chunk has no open stream");
    }
    if (chunk.sequence !== stream.sequence || chunk.offset !== stream.offset) {
      await this.failBlob(chunk.streamId, "noncontiguous_chunk");
      throw new StreamGatewayError("STREAM_INVALID", "Blob chunk is not contiguous");
    }
    const nextOffset = stream.offset + BigInt(chunk.data.length);
    if (chunk.data.length > MAX_BINARY_DATA_BYTES || nextOffset > stream.metadata.limit) {
      await this.failBlob(chunk.streamId, "stream_overflow");
      throw new StreamGatewayError("STREAM_INVALID", "Blob stream exceeded its limit");
    }
    await stream.upload.write(chunk.sequence, chunk.offset, chunk.data, stream.controller.signal);
    stream.sequence += 1;
    stream.offset = nextOffset;
  }

  async closeBlob(body: JsonObject): Promise<void> {
    const streamId = requiredUuid(body["streamId"], "stream.close streamId");
    const stream = this.blobs.get(streamId);
    if (stream === undefined) throw new StreamGatewayError("STREAM_INVALID", "Stream is not open");
    const length = parseUint64(body["length"], "stream.close length");
    const sha256 = body["sha256"];
    if (
      typeof sha256 !== "string" || !SHA256.test(sha256)
      || length !== stream.offset
      || (stream.metadata.expectedLength !== undefined && length !== stream.metadata.expectedLength)
      || (stream.metadata.sha256 !== undefined && sha256 !== stream.metadata.sha256)
    ) {
      await this.failBlob(streamId, "invalid_close");
      throw new StreamGatewayError("STREAM_INVALID", "Stream close metadata is invalid");
    }
    this.blobs.delete(streamId);
    try {
      const result = await stream.upload.close(length, sha256, stream.controller.signal);
      stream.controller.abort("stream_closed");
      if (result !== undefined) await this.sendJson(result);
    } catch (error) {
      stream.controller.abort("close_failed");
      await stream.upload.cancel("close_failed");
      throw error;
    }
  }

  async cancelBlob(body: JsonObject): Promise<void> {
    const streamId = requiredUuid(body["streamId"], "stream.cancel streamId");
    if (!this.blobs.has(streamId)) throw new StreamGatewayError("STREAM_INVALID", "Stream is not open");
    await this.failBlob(streamId, "peer_cancelled");
  }

  async releaseBlob(body: JsonObject): Promise<void> {
    const blobId = requiredUuid(body["blobId"], "blob.release blobId");
    await this.blobRuntime.release?.(blobId, this.connectionSignal);
  }

  async openTerminal(body: JsonObject): Promise<void> {
    if (this.terminal !== undefined) throw new StreamGatewayError("STREAM_INVALID", "Terminal is already open");
    const controller = linkedController(this.connectionSignal);
    const terminalRef: { current: OpenTerminal | undefined } = { current: undefined };
    const output: TerminalOutput = {
      write: async (data, signal) => {
        const current = terminalRef.current;
        if (current === undefined || this.terminal !== current) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal is not ready");
        const operation = current.outboundTail.then(async () => {
          signal.throwIfAborted();
          if (this.terminal !== current) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal is not ready");
          const sequence = current.outboundSequence;
          await this.sendBinary(FrameKind.TerminalBytes, encodeTerminalPayload({
            terminalGeneration: current.generation,
            sequence,
            data,
          }), current.controller.signal);
          current.outboundSequence = sequence + 1n;
        });
        current.outboundTail = operation.catch(() => undefined);
        await operation;
      },
      reset: async (reason, signal) => {
        const current = terminalRef.current;
        if (current === undefined || this.terminal !== current) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal is not ready");
        const operation = current.outboundTail.then(async () => {
          signal.throwIfAborted();
          current.controller.signal.throwIfAborted();
          if (this.terminal !== current) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal is not ready");
          current.outboundSequence = 0n;
          await this.sendJson({
            type: "terminal.reset",
            body: { terminalGeneration: current.generation.toString(), reason },
          });
        });
        current.outboundTail = operation.catch(() => undefined);
        await operation;
      },
    };
    const opened = await this.terminalRuntime.open(body, output, controller.signal);
    if (opened.generation < 0n || opened.generation > MAX_UINT64) {
      controller.abort("invalid_generation");
      await opened.channel.close("invalid_generation");
      throw new StreamGatewayError("PROTOCOL_VIOLATION", "Terminal generation is invalid");
    }
    terminalRef.current = {
      generation: opened.generation,
      channel: opened.channel,
      controller,
      inboundSequence: 0n,
      outboundSequence: 0n,
      outboundTail: Promise.resolve(),
    };
    this.terminal = terminalRef.current;
    await this.sendJson({
      type: "terminal.ready",
      body: { terminalGeneration: opened.generation.toString(), ...(opened.body ?? {}) },
    });
  }

  async terminalBytes(payload: Uint8Array): Promise<void> {
    const terminal = this.terminal;
    if (terminal === undefined) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal is not open");
    const chunk = decodeTerminalPayload(payload);
    if (chunk.terminalGeneration !== terminal.generation || chunk.sequence !== terminal.inboundSequence) {
      terminal.inboundSequence = 0n;
      await terminal.channel.reset?.("sequence_gap", terminal.controller.signal);
      await this.sendJson({
        type: "terminal.reset",
        body: { terminalGeneration: terminal.generation.toString(), reason: "sequence_gap" },
      });
      throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal sequence is not contiguous");
    }
    await terminal.channel.write(chunk.data, terminal.controller.signal);
    terminal.inboundSequence += 1n;
  }

  async resizeTerminal(body: JsonObject): Promise<void> {
    const terminal = this.requireTerminal(body);
    const columns = body["columns"];
    const rows = body["rows"];
    if (!Number.isInteger(columns) || !Number.isInteger(rows) || (columns as number) < 1 || (rows as number) < 1 || (columns as number) > 1000 || (rows as number) > 1000) {
      throw new StreamGatewayError("PROTOCOL_VIOLATION", "Terminal dimensions are invalid");
    }
    await terminal.channel.resize?.(columns as number, rows as number, terminal.controller.signal);
  }

  async terminalHistory(body: JsonObject): Promise<void> {
    if (this.terminalRuntime.history === undefined) throw new StreamGatewayError("PROTOCOL_VIOLATION", "Terminal history is unavailable");
    const result = await this.terminalRuntime.history(body, this.connectionSignal);
    await this.sendJson({ type: "terminal.history.result", body: result });
  }

  async closeTerminal(reason = "peer_closed"): Promise<void> {
    const terminal = this.terminal;
    if (terminal === undefined) return;
    this.terminal = undefined;
    terminal.controller.abort(reason);
    await terminal.channel.close(reason);
  }

  async cancelAll(reason: string): Promise<void> {
    const cancellations = [...this.blobs.entries()].map(async ([streamId, stream]) => {
      this.blobs.delete(streamId);
      stream.controller.abort(reason);
      await stream.upload.cancel(reason);
    });
    cancellations.push(this.closeTerminal(reason));
    await Promise.allSettled(cancellations);
  }

  private requireTerminal(body: JsonObject): OpenTerminal {
    const terminal = this.terminal;
    if (terminal === undefined) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal is not open");
    const generation = parseUint64(body["terminalGeneration"], "terminal generation");
    if (generation !== terminal.generation) throw new StreamGatewayError("TERMINAL_RESET_REQUIRED", "Terminal generation is stale");
    return terminal;
  }

  private async failBlob(streamId: string, reason: string): Promise<void> {
    const stream = this.blobs.get(streamId);
    if (stream === undefined) return;
    this.blobs.delete(streamId);
    stream.controller.abort(reason);
    await stream.upload.cancel(reason);
    await this.sendJson({ type: "stream.error", body: { streamId, code: "STREAM_INVALID" } });
  }
}

function parseBlobMetadata(body: JsonObject): BlobStreamMetadata {
  const streamId = requiredUuid(body["streamId"], "stream.open streamId");
  const purpose = boundedString(body["purpose"], "stream.open purpose", 64);
  const mediaType = boundedString(body["mediaType"], "stream.open mediaType", 128);
  const limit = parseUint64(body["limit"], "stream.open limit");
  const expectedLength = body["expectedLength"] === undefined ? undefined : parseUint64(body["expectedLength"], "stream.open expectedLength");
  const sha256 = body["sha256"];
  if (limit < 1n || limit > MAX_PROMPT_IMAGE_BYTES || (expectedLength !== undefined && expectedLength > limit) || (sha256 !== undefined && (typeof sha256 !== "string" || !SHA256.test(sha256)))) {
    throw new StreamGatewayError("STREAM_INVALID", "Stream metadata exceeds its bounds");
  }
  return {
    streamId,
    purpose,
    mediaType,
    limit,
    ...(expectedLength === undefined ? {} : { expectedLength }),
    ...(typeof sha256 === "string" ? { sha256 } : {}),
  };
}

function requiredUuid(value: unknown, label: string): string {
  if (typeof value !== "string" || !UUID_V4.test(value)) throw new StreamGatewayError("PROTOCOL_VIOLATION", `${label} is invalid`);
  return value;
}

function boundedString(value: unknown, label: string, maximum: number): string {
  if (typeof value !== "string" || value.length === 0 || value.length > maximum) throw new StreamGatewayError("PROTOCOL_VIOLATION", `${label} is invalid`);
  return value;
}

function parseUint64(value: unknown, label: string): bigint {
  if (typeof value !== "string" || !UINT64.test(value)) throw new StreamGatewayError("PROTOCOL_VIOLATION", `${label} is invalid`);
  const parsed = BigInt(value);
  if (parsed > MAX_UINT64) throw new StreamGatewayError("PROTOCOL_VIOLATION", `${label} is outside uint64`);
  return parsed;
}

function linkedController(parent: AbortSignal): AbortController {
  const controller = new AbortController();
  if (parent.aborted) controller.abort(parent.reason);
  else parent.addEventListener("abort", () => controller.abort(parent.reason), { once: true });
  return controller;
}
