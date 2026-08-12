import {
  MAX_VOICE_BODY_BYTES,
  MAX_VOICE_TEXT_CHARS,
  ProtocolError,
  VoicePcmStream,
  decodeStreamPayload,
  type JsonObject,
} from "@pimobile/protocol";
import type { OutboundMessage, VoiceAudioChunk, VoiceRuntime } from "./types.js";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u;
const ERROR_CODE = /^[A-Z][A-Z0-9_]{1,63}$/u;
const UINT64 = /^(0|[1-9][0-9]{0,19})$/u;
const MAX_UINT64 = 18_446_744_073_709_551_615n;

interface ActiveVoiceStream {
  readonly streamId: string;
  readonly pcm: VoicePcmStream;
  readonly controller: AbortController;
  outputTail: Promise<void>;
  cumulative: string;
  revision: bigint;
  inputFinal: boolean;
  failed: boolean;
}

export type VoiceJsonSend = (message: OutboundMessage) => Promise<void>;

export class VoiceStreamManager {
  private active: ActiveVoiceStream | undefined;

  constructor(
    private readonly runtime: VoiceRuntime | undefined,
    private readonly sendJson: VoiceJsonSend,
    private readonly connectionSignal: AbortSignal,
  ) {}

  start(body: JsonObject): void {
    if (this.runtime === undefined) throw new ProtocolError("PROTOCOL_VIOLATION", "Voice runtime is unavailable");
    const streamId = body["streamId"];
    if (
      typeof streamId !== "string" || !UUID_V4.test(streamId) || body["sampleRate"] !== 16_000 ||
      body["channels"] !== 1 || body["sampleFormat"] !== "s16le"
    ) throw new ProtocolError("PROTOCOL_VIOLATION", "voice.start body is invalid");
    if (this.active !== undefined) throw new ProtocolError("RESOURCE_EXHAUSTED", "A voice stream is already active");
    const controller = linkedController(this.connectionSignal);
    this.active = {
      streamId,
      pcm: new VoicePcmStream(streamId),
      controller,
      outputTail: Promise.resolve(),
      cumulative: "",
      revision: 0n,
      inputFinal: false,
      failed: false,
    };
  }

  audioPcm(payload: Uint8Array): void {
    const stream = this.requireActive();
    try {
      if (stream.inputFinal) throw new ProtocolError("STREAM_INVALID", "Voice stream input is closed");
      const frame = decodeStreamPayload(payload);
      stream.pcm.accept(frame.streamId, frame.sequence, frame.offset, frame.data);
    } catch (error) {
      this.failInbound(stream, error);
    }
  }

  boundary(body: JsonObject): void {
    const stream = this.active;
    try {
      const sessionId = body["sessionId"];
      const chunkSequence = body["chunkSequence"];
      const final = body["final"];
      if (typeof sessionId !== "string" || typeof chunkSequence !== "string" || !UINT64.test(chunkSequence) || typeof final !== "boolean" || body["audio"] !== undefined) {
        throw new ProtocolError("PROTOCOL_VIOLATION", "voice.audio body is invalid");
      }
      const numericSequence = BigInt(chunkSequence);
      if (numericSequence > MAX_UINT64) throw new ProtocolError("PROTOCOL_VIOLATION", "voice.audio chunkSequence exceeds uint64");
      if (stream === undefined) throw new ProtocolError("STREAM_INVALID", "Voice stream is not open");
      const assembled = stream.pcm.boundary(sessionId, chunkSequence, final);
      stream.inputFinal = final;
      const chunk: VoiceAudioChunk = {
        sessionId: stream.streamId,
        chunkSequence: assembled.sequence,
        final,
        pcm16le: assembled.pcm16le,
      };
      const runtime = this.runtime;
      if (runtime === undefined) throw new ProtocolError("PROTOCOL_VIOLATION", "Voice runtime is unavailable");
      const settled: Promise<{ readonly kind: "empty" } | { readonly kind: "text"; readonly text: string } | { readonly kind: "error"; readonly error: unknown }> =
        assembled.pcm16le.length === 0
          ? Promise.resolve({ kind: "empty" })
          : runtime.submit(chunk, stream.controller.signal).then(
            (text) => ({ kind: "text" as const, text }),
            (error: unknown) => ({ kind: "error" as const, error }),
          );
      const output = stream.outputTail.then(async () => {
        stream.controller.signal.throwIfAborted();
        const result = await settled;
        if (result.kind === "error") throw result.error;
        if (result.kind === "text") {
          stream.cumulative = mergeCumulativeTranscript(stream.cumulative, result.text);
          assertTranscript(stream.cumulative);
          if (stream.cumulative.length > 0) {
            stream.revision += 1n;
            await this.sendBounded({
              type: "voice.partial",
              body: {
                sessionId: stream.streamId,
                chunkSequence: assembled.sequence.toString(),
                revision: stream.revision.toString(),
                text: stream.cumulative,
              },
            }, stream.controller.signal);
          }
        }
        if (final) {
          await this.sendBounded({
            type: "voice.finish",
            body: {
              sessionId: stream.streamId,
              chunkSequence: assembled.sequence.toString(),
              text: stream.cumulative,
            },
          }, stream.controller.signal);
          if (this.active === stream) this.active = undefined;
          stream.controller.abort("voice_finished");
        }
      });
      stream.outputTail = output.catch(async (error: unknown) => {
        await this.fail(stream, error);
        throw error;
      });
      void stream.outputTail.catch(() => undefined);
    } catch (error) {
      if (stream === undefined) throw error;
      this.failInbound(stream, error);
    }
  }

  async cancel(body: JsonObject): Promise<void> {
    const streamId = body["streamId"];
    const reason = body["reason"];
    if (typeof streamId !== "string" || !UUID_V4.test(streamId) || typeof reason !== "string" || reason.length === 0 || reason.length > 128) {
      throw new ProtocolError("PROTOCOL_VIOLATION", "voice.cancel body is invalid");
    }
    const stream = this.requireActive();
    if (streamId !== stream.streamId) throw new ProtocolError("STREAM_INVALID", "Voice stream identity is invalid");
    this.cancelStream(stream, reason);
    await stream.outputTail.catch(() => undefined);
  }

  async cancelAll(reason: string): Promise<void> {
    const stream = this.active;
    if (stream === undefined) return;
    this.cancelStream(stream, reason);
    await stream.outputTail.catch(() => undefined);
  }

  private requireActive(): ActiveVoiceStream {
    const stream = this.active;
    if (stream === undefined) throw new ProtocolError("STREAM_INVALID", "Voice stream is not open");
    return stream;
  }

  private cancelStream(stream: ActiveVoiceStream, reason: string): void {
    if (this.active === stream) this.active = undefined;
    stream.pcm.cancel();
    stream.controller.abort(reason);
  }

  private failInbound(stream: ActiveVoiceStream, error: unknown): void {
    void this.fail(stream, error).catch(() => undefined);
  }

  private async fail(stream: ActiveVoiceStream, error: unknown): Promise<void> {
    if (stream.failed || stream.controller.signal.aborted) return;
    stream.failed = true;
    if (this.active === stream) this.active = undefined;
    stream.pcm.cancel();
    const rawCode = error instanceof Error && "code" in error ? (error as Error & { code?: unknown }).code : undefined;
    const code = typeof rawCode === "string" && ERROR_CODE.test(rawCode) ? rawCode : "VOICE_FAILED";
    const body: JsonObject = {
      streamId: stream.streamId,
      code,
      message: "Voice transcription failed",
    };
    if (code === "VOICE_QUOTA" && error instanceof Error && ERROR_CODE.test(error.message)) body["detailCode"] = error.message;
    const resetAtMs = error instanceof Error && "resetAtMs" in error ? (error as Error & { resetAtMs?: unknown }).resetAtMs : undefined;
    if (typeof resetAtMs === "number" && Number.isSafeInteger(resetAtMs) && resetAtMs >= 0) body["resetAtEpochMilliseconds"] = String(resetAtMs);
    stream.controller.abort(code);
    await this.sendBounded({ type: "voice.error", body }, this.connectionSignal);
  }

  private async sendBounded(message: OutboundMessage, signal: AbortSignal): Promise<void> {
    signal.throwIfAborted();
    if (Buffer.byteLength(JSON.stringify(message.body), "utf8") > MAX_VOICE_BODY_BYTES) {
      throw new Error("VOICE_RESPONSE_INVALID");
    }
    await this.sendJson(message);
  }
}

export function mergeCumulativeTranscript(previous: string, next: string): string {
  const left = previous.trim();
  const right = next.trim();
  if (left.length === 0) return right;
  if (right.length === 0 || left === right) return left;
  const leftTokens = transcriptTokens(left);
  const rightTokens = transcriptTokens(right);
  const maximum = Math.min(leftTokens.length, rightTokens.length);
  let overlap = 0;
  for (let count = maximum; count > 0; count -= 1) {
    const leftStart = leftTokens.length - count;
    if (rightTokens.slice(0, count).every((token, index) => token.normalized === leftTokens[leftStart + index]?.normalized)) {
      overlap = count;
      break;
    }
  }
  if (overlap === rightTokens.length && overlap > 0) return left;
  const append = overlap === 0 ? right : right.slice(rightTokens[overlap]?.start).trim();
  return append.length === 0 ? left : `${left} ${append}`;
}

interface TranscriptToken {
  readonly normalized: string;
  readonly start: number;
}

function transcriptTokens(text: string): TranscriptToken[] {
  const tokens: TranscriptToken[] = [];
  const pattern = /[\p{L}\p{N}][\p{L}\p{M}\p{N}]*(?:['’ʼ\u02bc][\p{L}\p{M}\p{N}]+)*/gu;
  for (const match of text.matchAll(pattern)) {
    const normalized = match[0]
      .normalize("NFKC")
      .toLocaleLowerCase("en-US")
      .replace(/['’ʼ\u02bc]/gu, "")
      .replace(/ß/gu, "ss")
      .replace(/ς/gu, "σ");
    if (normalized.length > 0) tokens.push({ normalized, start: match.index });
  }
  return tokens;
}

function assertTranscript(text: string): void {
  if (text.length > MAX_VOICE_TEXT_CHARS) {
    const error = new Error("Voice transcription exceeds its bound") as Error & { code: string };
    error.code = "VOICE_RESPONSE_INVALID";
    throw error;
  }
}

function linkedController(parent: AbortSignal): AbortController {
  const controller = new AbortController();
  if (parent.aborted) controller.abort(parent.reason);
  else parent.addEventListener("abort", () => controller.abort(parent.reason), { once: true });
  return controller;
}
